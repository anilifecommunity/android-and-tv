package com.anilili.data.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteOpenHelper
import com.anilili.data.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.UUID

internal data class CloudBinding(val accountId: String, val remoteProfileId: String) {
    init { validateLocalId(accountId); validateLocalId(remoteProfileId) }
    val scope: String get() = "account:$accountId:$remoteProfileId"
}

internal data class OutboxOperation(
    val opId: String,
    val binding: CloudBinding,
    val collection: LocalCollection,
    val entityKey: String,
    val baseRevision: Long,
    val action: String,
    val value: JsonObject?,
    val sent: Boolean,
    val blocked: Boolean,
)

internal enum class MutationOrigin { LOCAL, REMOTE, MIGRATION }

/** One transaction owns the local entity, its original sync revision, and the pending intent. */
internal class MediaDatabase(context: Context, name: String = "anilife-media.db") : SQLiteOpenHelper(
    context, name, null, 1,
    { throw SQLiteDatabaseCorruptException("Corrupt media database preserved for recovery") },
) {
    private val mutex = Mutex()
    private val _changes = MutableStateFlow(0L)
    val changes = _changes.asStateFlow()

    init { setWriteAheadLoggingEnabled(true) }

    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        for (collection in LocalCollection.entries) {
            val parent = when (collection) {
                LocalCollection.LIST_MEMBER -> "custom_lists"
                LocalCollection.GROUP_MEMBER -> "groups"
                else -> null
            }
            val foreign = parent?.let { ", FOREIGN KEY(profile_id, scope, parent_id) REFERENCES $it(profile_id, scope, entity_key) ON DELETE CASCADE" }.orEmpty()
            db.execSQL("CREATE TABLE ${collection.table} (profile_id TEXT NOT NULL, scope TEXT NOT NULL, entity_key TEXT NOT NULL, kind TEXT, media_key TEXT, parent_id TEXT, payload TEXT NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(profile_id, scope, entity_key)$foreign)")
            db.execSQL("CREATE INDEX ${collection.table}_media ON ${collection.table}(profile_id, scope, kind, media_key)")
        }
        db.execSQL("CREATE TABLE profile_bindings (profile_id TEXT PRIMARY KEY, account_id TEXT NOT NULL, remote_profile_id TEXT NOT NULL, active INTEGER NOT NULL CHECK(active IN (0,1)))")
        db.execSQL("CREATE TABLE sync_outbox (sequence INTEGER PRIMARY KEY AUTOINCREMENT, profile_id TEXT NOT NULL, scope TEXT NOT NULL, account_id TEXT NOT NULL, remote_profile_id TEXT NOT NULL, op_id TEXT NOT NULL UNIQUE, collection TEXT NOT NULL, entity_key TEXT NOT NULL, parent_id TEXT, base_revision INTEGER NOT NULL, action TEXT NOT NULL CHECK(action IN ('upsert','delete')), payload TEXT, sent INTEGER NOT NULL DEFAULT 0, blocked INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX sync_outbox_pending ON sync_outbox(profile_id, scope, sequence)")
        db.execSQL("CREATE TABLE entity_revisions (profile_id TEXT NOT NULL, scope TEXT NOT NULL, collection TEXT NOT NULL, entity_key TEXT NOT NULL, revision INTEGER NOT NULL, PRIMARY KEY(profile_id, scope, collection, entity_key))")
        db.execSQL("CREATE TABLE sync_cursors (profile_id TEXT NOT NULL, scope TEXT NOT NULL, cursor TEXT NOT NULL, PRIMARY KEY(profile_id, scope))")
        db.execSQL("CREATE TABLE legacy_sections (profile_id TEXT NOT NULL, scope TEXT NOT NULL, section TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(profile_id, scope, section))")
        db.execSQL("CREATE TABLE migration_state (profile_id TEXT NOT NULL, name TEXT NOT NULL, source_sha256 TEXT NOT NULL, completed_at INTEGER NOT NULL, PRIMARY KEY(profile_id, name))")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unsupported media database upgrade $oldVersion to $newVersion")
    }

    suspend fun <T> transaction(
        profileId: String,
        origin: MutationOrigin = MutationOrigin.LOCAL,
        expectedBinding: CloudBinding? = null,
        block: Transaction.() -> T,
    ): T = withContext(Dispatchers.IO) {
        validateLocalId(profileId)
        mutex.withLock {
            val db = writableDatabase
            db.beginTransaction()
            var committed = false
            var tx: Transaction? = null
            var failure: Throwable? = null
            try {
                val active = Transaction(db, profileId, origin, expectedBinding).also { tx = it }
                val result = active.block()
                db.setTransactionSuccessful()
                committed = true
                result
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                try {
                    db.endTransaction()
                } catch (cleanup: Throwable) {
                    // SQLITE_FULL can already have rolled back the native transaction.
                    if (failure == null) throw cleanup else failure.addSuppressed(cleanup)
                }
                if (committed && tx?.changed == true) _changes.value += 1
            }
        }
    }

    inner class Transaction internal constructor(
        private val db: SQLiteDatabase,
        private val profile: String,
        private val origin: MutationOrigin,
        expectedBinding: CloudBinding?,
    ) {
        internal var changed = false
        private var binding = readBinding(db, profile)
        private var scope = if (origin == MutationOrigin.MIGRATION) GUEST else binding?.scope ?: GUEST

        init {
            if (expectedBinding != null) check(binding == expectedBinding) { "Cloud profile is no longer active" }
            if (origin == MutationOrigin.REMOTE) require(expectedBinding != null)
        }

        fun entities(collection: LocalCollection, kind: MediaKind? = null, limit: Int = 250, offset: Int = 0): List<LocalEntity> {
            require(limit in 1..1000 && offset >= 0)
            val args = mutableListOf(profile, scopeFor(collection)).apply { if (kind != null) add(kind.name) }
            return db.query(collection.table, arrayOf("payload"), "profile_id=? AND scope=?" + (if (kind == null) "" else " AND kind=?"),
                args.toTypedArray(), null, null, "updated_at DESC, entity_key", "$offset,$limit").use { rows ->
                buildList { while (rows.moveToNext()) add(LocalEntity(collection, storageJson.parseToJsonElement(rows.getString(0)).jsonObject)) }
            }
        }

        fun entity(collection: LocalCollection, key: String): LocalEntity? = db.query(collection.table,
            arrayOf("payload"), "profile_id=? AND scope=? AND entity_key=?", arrayOf(profile, scopeFor(collection), key), null, null, null).use {
            if (it.moveToFirst()) LocalEntity(collection, storageJson.parseToJsonElement(it.getString(0)).jsonObject) else null
        }

        fun put(entity: LocalEntity): Boolean {
            if (origin == MutationOrigin.REMOTE) require(entity.collection.portable)
            if (this.entity(entity.collection, entity.key) == entity) return false
            requireNoRemoteOverwrite(entity.collection, entity.key)
            val row = ContentValues().apply {
                put("profile_id", profile); put("scope", scopeFor(entity.collection)); put("entity_key", entity.key)
                put("kind", entity.ref?.kind?.name); put("media_key", entity.ref?.mediaKey); put("parent_id", entity.parentId)
                put("payload", entity.value.toString()); put("updated_at", System.currentTimeMillis())
            }
            // REPLACE would cascade-delete memberships when renaming a list/group.
            val updated = db.update(entity.collection.table, row, "profile_id=? AND scope=? AND entity_key=?", arrayOf(profile, scopeFor(entity.collection), entity.key))
            if (updated == 0) db.insertOrThrow(entity.collection.table, null, row)
            queue(entity.collection, entity.key, "upsert", entity.portableValueIfEligible(), entity.parentId)
            changed = true
            return true
        }

        fun remove(collection: LocalCollection, key: String): Boolean {
            if (origin == MutationOrigin.REMOTE) require(collection.portable)
            val previous = entity(collection, key) ?: return false
            requireNoRemoteOverwrite(collection, key)
            val child = when (collection) { LocalCollection.LIST -> LocalCollection.LIST_MEMBER; LocalCollection.GROUP -> LocalCollection.GROUP_MEMBER; else -> null }
            if (child != null) {
                val args = arrayOf(profile, scope, child.name, key)
                if (origin == MutationOrigin.REMOTE) check(!db.query("sync_outbox", arrayOf("op_id"), "profile_id=? AND scope=? AND collection=? AND parent_id=?", args, null, null, null, "1").use { it.moveToFirst() }) { "Remote deletion conflicts with local membership intent" }
                db.delete("sync_outbox", "profile_id=? AND scope=? AND collection=? AND parent_id=?", args)
            }
            db.delete(collection.table, "profile_id=? AND scope=? AND entity_key=?", arrayOf(profile, scopeFor(collection), key))
            queue(collection, key, "delete", null, previous.parentId)
            changed = true
            return true
        }

        fun activateBinding(next: CloudBinding, replaceExisting: Boolean = false) {
            check(origin == MutationOrigin.LOCAL)
            val previous = db.query("profile_bindings", arrayOf("account_id", "remote_profile_id"), "profile_id=?", arrayOf(profile), null, null, null).use {
                if (it.moveToFirst()) CloudBinding(it.getString(0), it.getString(1)) else null
            }
            require(previous == null || previous == next || replaceExisting) { "Changing a cloud account/profile requires an explicit choice" }
            if (binding == next) return
            db.insertWithOnConflict("profile_bindings", null, ContentValues().apply {
                put("profile_id", profile); put("account_id", next.accountId); put("remote_profile_id", next.remoteProfileId); put("active", 1)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            binding = next
            scope = next.scope
            changed = true
        }

        fun signOut(expected: CloudBinding) {
            check(origin == MutationOrigin.LOCAL)
            check(binding == expected)
            db.update("profile_bindings", ContentValues().apply { put("active", 0) }, "profile_id=?", arrayOf(profile))
            binding = null
            scope = GUEST
            changed = true
        }

        fun outbox(includeBlocked: Boolean = false): List<OutboxOperation> {
            val activeBinding = binding ?: return emptyList()
            if (scope == GUEST) return emptyList()
            return db.query("sync_outbox", arrayOf("op_id", "collection", "entity_key", "base_revision", "action", "payload", "sent", "blocked"),
                "profile_id=? AND scope=?" + (if (includeBlocked) "" else " AND blocked=0"), arrayOf(profile, scope), null, null, "sequence", "50").use {
                buildList { while (it.moveToNext()) add(OutboxOperation(it.getString(0), activeBinding, LocalCollection.valueOf(it.getString(1)), it.getString(2), it.getLong(3), it.getString(4),
                    if (it.isNull(5)) null else storageJson.parseToJsonElement(it.getString(5)).jsonObject, it.getInt(6) != 0, it.getInt(7) != 0)) }
            }
        }

        /** Mark before returning any bytes to transport: retries can never rewrite an uncertain request. */
        fun claimOutbox(): List<OutboxOperation> {
            check(binding != null && origin != MutationOrigin.MIGRATION)
            var bytes = 0
            val pending = outbox().distinctBy { it.collection to it.entityKey }.takeWhile {
                bytes += (it.value?.toString()?.toByteArray(Charsets.UTF_8)?.size ?: 0) + 1024
                bytes <= 128 * 1024 - 4096
            }
            for (op in pending) db.update("sync_outbox", ContentValues().apply { put("sent", 1) }, "op_id=? AND profile_id=? AND scope=?", arrayOf(op.opId, profile, scope))
            return pending.map { it.copy(sent = true) }
        }

        fun acknowledge(opId: String, revision: Long): Boolean {
            require(revision > 0)
            val operation = db.query("sync_outbox", arrayOf("collection", "entity_key", "sent", "blocked"), "profile_id=? AND scope=? AND op_id=?",
                arrayOf(profile, scope, opId), null, null, null).use {
                if (!it.moveToFirst()) return false
                check(it.getInt(2) != 0 && it.getInt(3) == 0) { "An unsent operation cannot be acknowledged" }
                LocalCollection.valueOf(it.getString(0)) to it.getString(1)
            }
            db.delete("sync_outbox", "profile_id=? AND scope=? AND op_id=?", arrayOf(profile, scope, opId))
            val nextRevision = maxOf(revision, revision(operation.first, operation.second))
            recordRevision(operation.first, operation.second, nextRevision)
            // Supersede a never-sent successor after our receipt; claimed operations remain immutable.
            db.update("sync_outbox", ContentValues().apply {
                put("op_id", UUID.randomUUID().toString()); put("base_revision", nextRevision)
            }, "profile_id=? AND scope=? AND collection=? AND entity_key=? AND sent=0",
                arrayOf(profile, scope, operation.first.name, operation.second))
            changed = true
            return true
        }

        fun recordRevision(collection: LocalCollection, key: String, revision: Long) {
            require(revision >= 0 && scope != GUEST)
            val previous = revision(collection, key)
            require(revision >= previous) { "Server revision cannot move backwards" }
            if (revision == previous) return
            db.insertWithOnConflict("entity_revisions", null, ContentValues().apply {
                put("profile_id", profile); put("scope", scope); put("collection", collection.name); put("entity_key", key); put("revision", revision)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }

        fun cursor(): String? = db.query("sync_cursors", arrayOf("cursor"), "profile_id=? AND scope=?", arrayOf(profile, scope), null, null, null).use { if (it.moveToFirst()) it.getString(0) else null }

        fun setCursor(value: String) {
            require(scope != GUEST && Regex("[A-Za-z0-9_-]{16,512}").matches(value))
            if (cursor() == value) return
            db.insertWithOnConflict("sync_cursors", null, ContentValues().apply { put("profile_id", profile); put("scope", scope); put("cursor", value) }, SQLiteDatabase.CONFLICT_REPLACE)
            changed = true
        }

        fun migrationComplete(name: String): Boolean = db.query("migration_state", arrayOf("name"), "profile_id=? AND name=?", arrayOf(profile, name), null, null, null).use { it.moveToFirst() }

        fun legacySection(name: String): String? = db.query("legacy_sections", arrayOf("payload"), "profile_id=? AND scope=? AND section=?", arrayOf(profile, scope, name), null, null, null).use { if (it.moveToFirst()) it.getString(0) else null }

        fun retainLegacySection(name: String, raw: String) {
            check(origin == MutationOrigin.MIGRATION)
            validateLocalId(name)
            require(raw.toByteArray(Charsets.UTF_8).size <= 8 * 1024 * 1024)
            db.insertOrThrow("legacy_sections", null, ContentValues().apply { put("profile_id", profile); put("scope", scope); put("section", name); put("payload", raw) })
            changed = true
        }

        fun completeMigration(name: String, sourceSha256: String, expectedCounts: Map<LocalCollection, Int>) {
            check(origin == MutationOrigin.MIGRATION)
            validateLocalId(name)
            require(Regex("[0-9a-f]{64}").matches(sourceSha256))
            require(LocalCollection.entries.all { type ->
                val expected = expectedCounts.getOrDefault(type, 0)
                expected >= 0 && db.rawQuery("SELECT COUNT(*) FROM ${type.table} WHERE profile_id=? AND scope=?", arrayOf(profile, scopeFor(type))).use { it.moveToFirst(); it.getInt(0) == expected }
            }) { "Migration counts differ" }
            db.insertOrThrow("migration_state", null, ContentValues().apply {
                put("profile_id", profile); put("name", name); put("source_sha256", sourceSha256); put("completed_at", System.currentTimeMillis())
            })
            changed = true
        }

        private fun requireNoRemoteOverwrite(collection: LocalCollection, key: String) {
            if (origin != MutationOrigin.REMOTE) return
            check(!db.query("sync_outbox", arrayOf("op_id"), "profile_id=? AND scope=? AND collection=? AND entity_key=?", arrayOf(profile, scope, collection.name, key), null, null, null, "1").use { it.moveToFirst() }) {
                "Remote changes conflict with pending local intent"
            }
        }

        private fun scopeFor(collection: LocalCollection): String = if (collection == LocalCollection.LOCAL_SETTING) LOCAL_PROFILE else scope

        private fun revision(collection: LocalCollection, key: String): Long = db.query("entity_revisions", arrayOf("revision"),
            "profile_id=? AND scope=? AND collection=? AND entity_key=?", arrayOf(profile, scope, collection.name, key), null, null, null).use { if (it.moveToFirst()) it.getLong(0) else 0L }

        private fun queue(collection: LocalCollection, key: String, action: String, payload: JsonObject?, parentId: String?) {
            val activeBinding = binding ?: return
            if (origin != MutationOrigin.LOCAL || !collection.portable) return
            val raw = payload?.toString()
            val unsent = db.query("sync_outbox", arrayOf("op_id"), "profile_id=? AND scope=? AND collection=? AND entity_key=? AND sent=0", arrayOf(profile, scope, collection.name, key), null, null, "sequence DESC", "1").use { if (it.moveToFirst()) it.getString(0) else null }
            val values = ContentValues().apply {
                put("action", action); put("payload", raw); put("parent_id", parentId)
                put("blocked", if ((raw?.toByteArray(Charsets.UTF_8)?.size ?: 0) + 1024 > MAX_OPERATION_BYTES) 1 else 0)
            }
            if (unsent != null) db.update("sync_outbox", values, "op_id=?", arrayOf(unsent))
            else {
                values.put("profile_id", profile); values.put("scope", scope); values.put("account_id", activeBinding.accountId); values.put("remote_profile_id", activeBinding.remoteProfileId)
                values.put("op_id", UUID.randomUUID().toString()); values.put("collection", collection.name); values.put("entity_key", key); values.put("base_revision", revision(collection, key))
                db.insertOrThrow("sync_outbox", null, values)
            }
        }
    }

    private fun readBinding(db: SQLiteDatabase, profile: String): CloudBinding? = db.query("profile_bindings", arrayOf("account_id", "remote_profile_id"),
        "profile_id=? AND active=1", arrayOf(profile), null, null, null).use { if (it.moveToFirst()) CloudBinding(it.getString(0), it.getString(1)) else null }

    private fun LocalEntity.portableValueIfEligible() = if (collection.portable) portableValue() else null

    private companion object { const val GUEST = "guest"; const val LOCAL_PROFILE = "local-profile" }
}
