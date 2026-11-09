package io.github.edrock200.appdownloader.install

import java.io.IOException
import java.io.InputStream

/** A narrow command interface, also used to verify session rollback without a device. */
fun interface InstallCommandRunner {
    fun run(arguments: List<String>, input: InputStream?): String
}

data class InstallSource(val name: String, val size: Long, val input: InputStream)

/** Uses Android's session commands under Shizuku's shell identity; no shell interpolation. */
class ShellPackageInstaller(private val commands: InstallCommandRunner) {
    fun install(sources: List<InstallSource>, packageName: String, userId: Int) {
        require(sources.isNotEmpty() && sources.size <= 128) { "No valid APKs to install" }
        require(sources.all { it.size > 0 && it.name.matches(Regex("[A-Za-z0-9._-]+\\.apk")) }) { "Invalid APK input" }
        require(sources.map { it.name }.distinct().size == sources.size) { "Duplicate APK names" }
        require(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) && userId >= 0)
        val size = sources.fold(0L) { total, source ->
            require(total <= Long.MAX_VALUE - source.size) { "APK input is too large" }
            total + source.size
        }
        val created = commands.run(listOf("install-create", "-r", "--user", "$userId", "--pkg", packageName, "-S", "$size"), null)
        requireSuccess(created)
        val session = Regex("\\[(\\d+)]").find(created)?.groupValues?.get(1)
            ?: throw IOException("Android did not return an install session")
        var committed = false
        try {
            sources.forEach { source ->
                requireSuccess(commands.run(listOf("install-write", "-S", "${source.size}", session, source.name, "-"), source.input))
            }
            requireSuccess(commands.run(listOf("install-commit", session), null))
            committed = true
        } finally {
            if (!committed) runCatching { commands.run(listOf("install-abandon", session), null) }
        }
    }

    private fun requireSuccess(output: String) {
        if (output.lineSequence().none { it.trim() == "Success" || it.trim().startsWith("Success:") }) {
            throw IOException(output.trim().take(700).ifEmpty { "Android did not confirm installation" })
        }
    }
}
