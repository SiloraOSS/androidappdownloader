package io.github.edrock200.appdownloader.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import io.github.edrock200.appdownloader.ui.Device

/** Stores the saved entries as a JSON file in the app's private storage. */
class EntryRepository(context: Context) {

    private val file = AtomicFile(File(context.filesDir, "entries.json"))
    private val isTv = Device.isTv(context)
    private val mutex = Mutex()
    private val serializer = ListSerializer(AppEntry.serializer())
    private val _entries = MutableStateFlow(Silora.entries(load(), isTv))

    val entries: StateFlow<List<AppEntry>> = _entries.asStateFlow()

    fun get(id: String): AppEntry? = _entries.value.firstOrNull { it.id == id }

    suspend fun add(entry: AppEntry) = mutate { it + entry }

    suspend fun update(id: String, transform: (AppEntry) -> AppEntry) =
        mutate { list -> list.map { if (it.id == id) transform(it) else it } }

    suspend fun delete(id: String) = mutate { list -> list.filterNot { it.id == id } }

    /** Replaces the whole list (used when restoring a backup). */
    suspend fun replaceAll(entries: List<AppEntry>) = mutate { entries }

    private suspend fun mutate(block: (List<AppEntry>) -> List<AppEntry>) = mutex.withLock {
        val updated = Silora.entries(block(_entries.value), isTv, get(Silora.ID))
        if (updated != _entries.value) withContext(NonCancellable) {
            withContext(Dispatchers.IO) { save(updated) }
            _entries.value = updated
        }
    }

    private fun load(): List<AppEntry> = try {
        file.openRead().bufferedReader().use { JSON.decodeFromString(serializer, it.readText()) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(list: List<AppEntry>) {
        val bytes = JSON.encodeToString(serializer, list).toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }

    companion object {
        val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }
    }
}
