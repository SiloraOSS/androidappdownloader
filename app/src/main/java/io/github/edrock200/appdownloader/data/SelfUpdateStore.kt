package io.github.edrock200.appdownloader.data

import android.content.Context
import android.util.AtomicFile
import io.github.edrock200.appdownloader.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Saved before committing a self-install: Android replaces (and kills) this process. */
class SelfUpdateStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "self-update.json"))
    private val mutex = Mutex()
    @Volatile var current: AppEntry = SiloraUpdater.entry(load()).let { saved ->
        // A successful replacement may never deliver the install callback to the old process.
        if (saved.readyVersionCode != null && saved.readyVersionCode <= BuildConfig.VERSION_CODE) {
            if (Settings(context).current.deleteApkAfterInstall) saved.readyFile?.let { File(it).delete() }
            saved.copy(readyFile = null, readyVersionName = null, readyVersionCode = null, readyTag = null)
        } else saved
    }
        private set

    suspend fun update(transform: (AppEntry) -> AppEntry): AppEntry = mutex.withLock {
        val updated = SiloraUpdater.entry(transform(current))
        withContext(Dispatchers.IO) {
            val output = file.startWrite()
            try {
                output.write(EntryRepository.JSON.encodeToString(AppEntry.serializer(), updated).toByteArray(Charsets.UTF_8))
                file.finishWrite(output)
            } catch (e: Exception) {
                file.failWrite(output)
                throw e
            }
        }
        current = updated
        updated
    }

    private fun load(): AppEntry? = runCatching {
        file.openRead().bufferedReader().use { EntryRepository.JSON.decodeFromString(AppEntry.serializer(), it.readText()) }
    }.getOrNull()
}
