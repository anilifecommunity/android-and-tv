package com.anilili.data.profiles

import android.app.Application
import android.os.Build
import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [22, 35], manifest = Config.NONE, application = Application::class)
class ProfileFileSwitchTest {
    @get:Rule val temporary = TemporaryFolder()

    @Before fun requireFrameworkRenameSemantics() {
        // shortcut: API 35 AtomicFile needs POSIX replacement; Linux CI covers it until Windows supports it.
        assumeFalse("Windows renameTo cannot replace the existing API 35 AtomicFile destination",
            Build.VERSION.SDK_INT >= 30 && File.separatorChar == '\\')
    }

    private fun file(directory: File, name: String, text: String): File =
        File(directory, name).apply { parentFile?.mkdirs(); writeText(text) }

    @Test fun `failed phase commit leaves live input intact and replay never resaves incoming data`() {
        val live = temporary.newFolder("live")
        val outgoing = temporary.newFolder("outgoing")
        val incoming = temporary.newFolder("incoming")
        val files = listOf(file(live, "library.xml", "A-library"), file(live, "tokens.xml", "A-token"))
        file(incoming, "library.xml", "B-library")
        file(incoming, "tokens.xml", "B-token")
        assertThrows(IOException::class.java) {
            ProfileFileSwitch.apply(files, outgoing, incoming, false, {}, { throw IOException("phase commit failed") })
        }
        assertEquals(listOf("A-library", "A-token"), files.map(File::readText))
        ProfileFileSwitch.apply(files, outgoing, incoming, false, {}, {})
        assertEquals(listOf("B-library", "B-token"), files.map(File::readText))
        // Simulate death after every file was installed but before active-profile commit.
        ProfileFileSwitch.apply(files, outgoing, incoming, true,
            { fail("prepared again after the durable phase") }, { fail("saved again after the durable phase") })
        assertEquals(listOf("A-library", "A-token"), files.map { File(outgoing, it.name).readText() })
        assertEquals(listOf("B-library", "B-token"), files.map { File(incoming, it.name).readText() })
    }

    @Test fun `partly installed incoming profile replays using retained sources`() {
        val live = temporary.newFolder("live")
        val outgoing = temporary.newFolder("outgoing")
        val incoming = temporary.newFolder("incoming")
        val files = listOf(file(live, "library.xml", "A-library"), file(live, "tokens.xml", "A-token"))
        file(incoming, "library.xml", "B-library")
        val blocked = File(incoming, "tokens.xml").apply { mkdir() }
        var saved = false
        assertThrows(IOException::class.java) {
            ProfileFileSwitch.apply(files, outgoing, incoming, saved, {}, { saved = true })
        }
        assertTrue(saved)
        assertEquals("B-library", files[0].readText())
        assertEquals("A-token", files[1].readText())
        assertTrue(blocked.delete())
        file(incoming, "tokens.xml", "B-token")
        ProfileFileSwitch.apply(files, outgoing, incoming, saved, {}, { fail("resaved partial incoming state") })
        assertEquals(listOf("B-library", "B-token"), files.map(File::readText))
        assertEquals(listOf("A-library", "A-token"), files.map { File(outgoing, it.name).readText() })
    }

    @Test fun `snapshot failure cannot start installing another profile`() {
        val live = temporary.newFolder("live")
        val outgoing = temporary.newFolder("outgoing")
        val incoming = temporary.newFolder("incoming")
        val files = listOf(file(live, "library.xml", "A-library"), file(live, "tokens.xml", "A-token"))
        file(incoming, "library.xml", "B-library")
        val blocked = File(outgoing, "tokens.xml").apply { mkdir() }
        var saved = false
        assertThrows(IOException::class.java) {
            ProfileFileSwitch.apply(files, outgoing, incoming, false, {}, { saved = true })
        }
        assertFalse(saved)
        assertEquals(listOf("A-library", "A-token"), files.map(File::readText))
        assertEquals("B-library", File(incoming, "library.xml").readText())
        assertTrue(blocked.delete())
        ProfileFileSwitch.apply(files, outgoing, incoming, false, {}, { saved = true })
        assertTrue(saved)
        assertEquals("A-token", File(outgoing, "tokens.xml").readText())
        assertFalse(files[1].exists())
    }

    @Test fun `empty profile clears real Android backups as well as live credentials`() {
        val live = temporary.newFolder("live")
        val outgoing = temporary.newFolder("outgoing")
        val incoming = temporary.newFolder("incoming")
        val tokens = file(live, "tokens.xml", "incomplete")
        file(live, "tokens.xml.bak", "A-token")
        file(live, "tokens.bak", "obsolete")
        ProfileFileSwitch.apply(listOf(tokens), outgoing, incoming, false, {}, {})
        assertEquals("A-token", File(outgoing, tokens.name).readText())
        assertFalse(tokens.exists())
        assertFalse(File(live, "tokens.xml.bak").exists())
        assertFalse(File(live, "tokens.bak").exists())
        ProfileFileSwitch.apply(listOf(tokens), outgoing, incoming, true, {}, {})
        assertEquals("A-token", File(outgoing, tokens.name).readText())
    }

    @Test fun `prepared new profile settings and absent old files survive repeated restoration`() {
        val live = temporary.newFolder("live")
        val outgoing = temporary.newFolder("outgoing")
        val incoming = temporary.newFolder("incoming")
        val settings = file(live, "settings.preferences_pb", "A-settings")
        val absent = File(live, "search.xml")
        file(outgoing, "search.xml", "stale old snapshot")
        var prepared = 0
        ProfileFileSwitch.apply(listOf(settings, absent), outgoing, incoming, false,
            { prepared++; file(incoming, settings.name, "B-fresh-layout-and-tabs") }, {})
        ProfileFileSwitch.apply(listOf(settings, absent), outgoing, incoming, true,
            { prepared++ }, { fail("resaved installed profile") })
        assertEquals(1, prepared)
        assertEquals("A-settings", File(outgoing, settings.name).readText())
        assertEquals("B-fresh-layout-and-tabs", settings.readText())
        assertFalse(absent.exists())
        assertFalse(File(outgoing, absent.name).exists())
    }
}
