package io.github.edrock200.appdownloader.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Stores the saved entries as a JSON file in the app's private storage. */
class EntryRepository(context: Context) {

    private val file = File(context.filesDir, "entries.json")
    private val mutex = Mutex()
    private val serializer = ListSerializer(AppEntry.serializer())
    private val _entries = MutableStateFlow(load())

    val entries: StateFlow<List<AppEntry>> = _entries.asStateFlow()

    fun get(id: String): AppEntry? = _entries.value.firstOrNull { it.id == id }

    suspend fun add(entry: AppEntry) = mutate { it + entry }

    suspend fun update(id: String, transform: (AppEntry) -> AppEntry) =
        mutate { list -> list.map { if (it.id == id) transform(it) else it } }

    suspend fun delete(id: String) = mutate { list -> list.filterNot { it.id == id } }

    private suspend fun mutate(block: (List<AppEntry>) -> List<AppEntry>) = mutex.withLock {
        val updated = block(_entries.value)
        _entries.value = updated
        withContext(Dispatchers.IO) { save(updated) }
    }

    private fun load(): List<AppEntry> = try {
        if (file.exists()) JSON.decodeFromString(serializer, file.readText()) else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(list: List<AppEntry>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSON.encodeToString(serializer, list))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
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
