package com.anilili.data.profiles

import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest

/** Keep immutable switch inputs until the active-profile commit, including across process death. */
internal object ProfileFileSwitch {
    fun apply(
        liveFiles: List<File>,
        outgoing: File,
        incoming: File,
        saved: Boolean,
        prepareIncoming: () -> Unit,
        markSaved: () -> Unit,
    ) {
        if (!saved) {
            liveFiles.forEach { live -> copyOrRemove(live, File(outgoing, live.name)) }
            prepareIncoming()
            markSaved()
        }
        liveFiles.forEach { live -> copyOrRemove(File(incoming, live.name), live) }
    }

    private fun copyOrRemove(source: File, target: File) {
        for (file in listOf(source, File("${source.path}.bak"), target, File("${target.path}.bak"))) {
            if (file.exists() && !file.isFile) throw IOException("Profile input or destination is not a file")
        }
        if (!source.exists() && !File("${source.path}.bak").exists()) {
            AtomicFile(target).delete()
            for (file in listOf(target, File("${target.path}.bak"), File("${target.path}.new"))) remove(file)
        } else {
            if (target.parentFile?.let { it.isDirectory || it.mkdirs() } != true) {
                throw IOException("Profile directory could not be created")
            }
            val destination = AtomicFile(target)
            var output: FileOutputStream? = null
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                AtomicFile(source).openRead().use { input ->
                    val stream = destination.startWrite().also { output = it }
                    DigestInputStream(input, digest).copyTo(stream)
                    stream.fd.sync()
                }
                destination.finishWrite(output)
                output = null
                val actual = MessageDigest.getInstance("SHA-256")
                AtomicFile(target).openRead().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        actual.update(buffer, 0, count)
                    }
                }
                // AtomicFile can log a failed rename without throwing; verify before advancing.
                if (!digest.digest().contentEquals(actual.digest())) throw IOException("Profile copy failed verification")
            } catch (error: Throwable) {
                if (output != null) destination.failWrite(output)
                throw error
            }
        }
        remove(File(target.parentFile, "${target.nameWithoutExtension}.bak"))
    }

    private fun remove(file: File) {
        if (file.exists() && !file.delete()) throw IOException("Profile file could not be removed")
    }
}
