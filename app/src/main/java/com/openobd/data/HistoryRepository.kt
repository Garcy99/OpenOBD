package com.openobd.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class StoredDtc(val code: String, val type: String, val ecu: String, val description: String)

@Serializable
data class ScanRecord(
    val timestamp: Long,
    val vin: String?,
    val milOn: Boolean,
    val codes: List<StoredDtc>,
    val cleared: Boolean = false,
    val note: String? = null,
    val vehicleId: String? = null,
)

/** Keeps a local history of DTC scans and clears, so you can see when codes came back. */
class HistoryRepository(context: Context) {
    private val file = File(context.filesDir, "scan_history.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val _records = MutableStateFlow<List<ScanRecord>>(emptyList())
    val records: StateFlow<List<ScanRecord>> = _records

    suspend fun load() = withContext(Dispatchers.IO) {
        _records.value = runCatching { json.decodeFromString<List<ScanRecord>>(file.readText()) }.getOrDefault(emptyList())
    }

    suspend fun add(record: ScanRecord) = withContext(Dispatchers.IO) {
        val list = (listOf(record) + _records.value).take(500)
        _records.value = list
        runCatching { file.writeText(json.encodeToString(list)) }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        _records.value = emptyList()
        file.delete()
    }
}
