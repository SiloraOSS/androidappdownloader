package io.github.edrock200.appdownloader.install

import android.content.Context
import android.util.AtomicFile
import io.github.edrock200.appdownloader.data.InstallRecord
import io.github.edrock200.appdownloader.data.Silora
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.MessageDigest

@Serializable
data class PendingInstallRestore(
    val record: InstallRecord,
    val sha256: String,
    val previousVersionName: String? = null,
    val previousVersionCode: Long? = null,
    val confirmed: Boolean = false,
    val installing: Boolean = false,
    val attemptId: String? = null,
)

data class InstallRestoreState(
    val pending: PendingInstallRestore? = null,
    val preparing: Boolean = false,
    val error: String? = null,
) {
    val active get() = pending != null || preparing
}

/** Survives recreation or process death while Android's uninstall/install screens are open. */
class InstallRestoreStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "install-restore")
    private val stateFile = AtomicFile(File(directory, "pending.json"))
    val apk = File(directory, "selected.apk")
    private val archive = File(context.cacheDir, "installed-apks")
    private val downloadDirectory = File(context.cacheDir, "apks")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): PendingInstallRestore? = try {
        json.decodeFromString<PendingInstallRestore>(stateFile.openRead().bufferedReader().use { it.readText() })
    } catch (_: Exception) { null }

    fun save(pending: PendingInstallRestore) {
        check(directory.isDirectory || directory.mkdirs()) { "Could not save restore progress" }
        val output = stateFile.startWrite()
        try {
            output.write(json.encodeToString(PendingInstallRestore.serializer(), pending).toByteArray())
            stateFile.finishWrite(output)
        } catch (e: Exception) {
            stateFile.failWrite(output)
            throw e
        }
    }

    fun clear() {
        stateFile.delete()
        apk.delete()
        File(directory, "download.part").delete()
        File(directory, "download.apk").delete()
    }

    fun localApk(record: InstallRecord): File? = record.apkPath?.let(::File)?.takeIf { file ->
        file.isFile && listOf(archive, downloadDirectory).any { file.canonicalFile.parentFile == it.canonicalFile }
    }

    fun stage(source: File) {
        check(directory.isDirectory || directory.mkdirs()) { "Could not save the restore APK" }
        source.copyTo(apk, overwrite = true)
    }

    /** Keeps a separate versioned copy so the next download cannot overwrite a past install. */
    fun retain(source: File, sha256: String): String {
        check(archive.isDirectory || archive.mkdirs()) { "Could not retain the installed APK" }
        val destination = File(archive, "$sha256.apk")
        if (!destination.isFile || InstallRestoreStore.sha256(destination) != sha256) {
            val part = File(archive, "$sha256.part")
            try {
                source.copyTo(part, overwrite = true)
                check(part.renameTo(destination)) { "Could not retain the installed APK" }
            } finally { part.delete() }
        }
        return destination.absolutePath
    }

    companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    digest.update(buffer, 0, size)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun validate(record: InstallRecord, info: ApkInfo, sha256: String) {
            if (info.packageName != Silora.PACKAGE || info.versionCode != record.versionCode || info.isSplit) {
                throw IOException("This APK does not match the selected Silora build. Nothing was uninstalled.")
            }
            if (record.apkSha256 != null && record.apkSha256 != sha256) {
                throw IOException("This APK has changed since it was installed. Restore was stopped.")
            }
        }
    }
}
