package io.github.edrock200.appdownloader

import io.github.edrock200.appdownloader.install.InstallCommandRunner
import io.github.edrock200.appdownloader.install.InstallSource
import io.github.edrock200.appdownloader.install.ShellPackageInstaller
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ShellPackageInstallerTest {
    private val calls = mutableListOf<List<String>>()
    private val streamed = mutableListOf<String>()
    private fun runner(fail: String? = null) = InstallCommandRunner { args, input ->
        calls += args
        input?.let { streamed += it.bufferedReader().readText() }
        when (args.first()) {
            fail -> "Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]"
            "install-create" -> "Success: created install session [12345]"
            "install-write" -> "Success: streamed APK"
            else -> "Success"
        }
    }
    private fun source(name: String = "0.apk", text: String = "apk") = InstallSource(name, text.length.toLong(), text.byteInputStream())

    @Test fun streamsAllSplitsBeforeCommittingToTheCallersProfile() {
        ShellPackageInstaller(runner()).install(listOf(source(), source("1.apk", "split")), "org.silora.app", 10)
        assertEquals(listOf("install-create", "-r", "--user", "10", "--pkg", "org.silora.app", "-S", "8"), calls.first())
        assertEquals(listOf("apk", "split"), streamed)
        assertEquals(listOf("install-commit", "12345"), calls.last())
        assertFalse(calls.any { it.first() == "install-abandon" })
    }

    @Test fun partialWriteFailureAbandonsTheWholeSession() {
        val error = assertThrows(IOException::class.java) {
            ShellPackageInstaller(runner("install-write")).install(listOf(source()), "org.silora.app", 0)
        }
        assertTrue(error.message!!.contains("INSUFFICIENT_STORAGE"))
        assertEquals(listOf("install-abandon", "12345"), calls.last())
        assertFalse(calls.any { it.first() == "install-commit" })
    }

    @Test fun commitFailureAlsoAbandonsTheSession() {
        assertThrows(IOException::class.java) {
            ShellPackageInstaller(runner("install-commit")).install(listOf(source()), "org.silora.app", 0)
        }
        assertEquals(listOf("install-abandon", "12345"), calls.last())
    }

    @Test fun commandExceptionStillAbandonsTheSessionAndKeepsTheOriginalError() {
        val broken = InstallCommandRunner { args, _ ->
            calls += args
            if (args.first() == "install-create") "Success: created install session [12345]"
            else throw IOException(if (args.first() == "install-write") "Shizuku stopped" else "Cleanup failed")
        }
        val error = assertThrows(IOException::class.java) {
            ShellPackageInstaller(broken).install(listOf(source()), "org.silora.app", 0)
        }
        assertEquals("Shizuku stopped", error.message)
        assertEquals(listOf("install-abandon", "12345"), calls.last())
    }

    @Test fun creationFailureDoesNotWriteOrCommitAnything() {
        assertThrows(IOException::class.java) {
            ShellPackageInstaller(runner("install-create")).install(listOf(source()), "org.silora.app", 0)
        }
        assertEquals(1, calls.size)
    }

    @Test fun invalidNamesAreRejectedBeforeStartingAnyCommand() {
        assertThrows(IllegalArgumentException::class.java) {
            ShellPackageInstaller(runner()).install(listOf(source("../bad.apk")), "org.silora.app", 0)
        }
        assertTrue(calls.isEmpty())
    }

    @Test fun overflowCannotProduceAnInvalidSessionSize() {
        assertThrows(IllegalArgumentException::class.java) {
            ShellPackageInstaller(runner()).install(listOf(
                InstallSource("0.apk", Long.MAX_VALUE, "".byteInputStream()), source("1.apk")), "org.silora.app", 0)
        }
        assertTrue(calls.isEmpty())
    }
}
