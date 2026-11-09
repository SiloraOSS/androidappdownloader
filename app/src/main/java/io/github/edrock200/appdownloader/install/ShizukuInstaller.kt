package io.github.edrock200.appdownloader.install

import android.content.ComponentName
import android.content.Context
import android.content.IntentSender
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import io.github.edrock200.appdownloader.BuildConfig
import io.github.edrock200.appdownloader.app
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

object ShizukuInstaller {
    suspend fun install(context: Context, file: File, info: ApkInfo, result: IntentSender) {
        val status = context.app().shizuku.awaitReady()
        if (!status.ready) throw IOException(status.help)
        val args = Shizuku.UserServiceArgs(ComponentName(context, ShizukuInstallService::class.java))
            .tag("silora-install-${Process.myUid()}")
            .processNameSuffix("shizuku_install")
            .version(BuildConfig.VERSION_CODE)
            .daemon(true)
        val connected = CompletableDeferred<IShizukuInstaller>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                connected.complete(IShizukuInstaller.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName) {
                connected.completeExceptionally(IOException("Shizuku disconnected. Start it and try again."))
            }
        }
        val descriptors = mutableListOf<ParcelFileDescriptor>()
        var extracted: File? = null
        try {
            val service = withTimeout(15_000) {
                withContext(Dispatchers.Main.immediate) { Shizuku.bindUserService(args, connection) }
                connected.await()
            }
            withContext(Dispatchers.IO) {
                if (service.uid !in listOf(0, 2000)) throw IOException("Shizuku did not provide installation privileges")
                val files = if (info.isSplit) {
                    val dir = File(context.cacheDir, "shizuku-${java.util.UUID.randomUUID()}")
                    if (!dir.mkdir()) throw IOException("Could not prepare split APKs")
                    extracted = dir
                    ZipFile(file).use { zip ->
                        info.splitEntries.mapIndexed { index, name ->
                            File(dir, "$index.apk").also { target ->
                                zip.getInputStream(zip.getEntry(name)).use { input -> target.outputStream().use { input.copyTo(it) } }
                            }
                        }
                    }
                } else listOf(file)
                files.forEach { descriptors += ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
                service.install(info.packageName, descriptors.toTypedArray(), Array(files.size) { "$it.apk" }, files.map { it.length() }.toLongArray(), result)
            }
        } finally {
            descriptors.forEach { runCatching { it.close() } }
            extracted?.deleteRecursively()
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                runCatching { Shizuku.unbindUserService(args, connection, true) }
            }
        }
    }
}
