package com.openobd.core.brand

import com.openobd.core.obd.Pids

/**
 * Data identifiers whose length and meaning are standardised. ISO 14229 / SAE J1979-2 map OBD PIDs to
 * DIDs F4xx (e.g. F40C = engine speed), which many ECUs use inside DTC snapshots.
 */
object DidCatalog {
    private val fixed = mapOf(
        0xF190 to (17 to "VIN"),
    )

    fun length(did: Int): Int? {
        if (did shr 8 == 0xF4) return Pids.all[did and 0xFF]?.bytes
        return fixed[did]?.first
    }

    fun decode(did: Int, bytes: IntArray): List<SnapshotValue> {
        if (did shr 8 == 0xF4) {
            val info = Pids.all[did and 0xFF]
            val values = info?.sensors?.mapNotNull { s ->
                val v = s.decodeSafe(bytes) ?: return@mapNotNull null
                val text = s.labeler?.invoke(v) ?: ("%.${s.decimals}f".format(v) + if (s.unit.symbol.isNotEmpty()) " ${s.unit.symbol}" else "")
                SnapshotValue(did, s.name, text)
            }.orEmpty()
            if (values.isNotEmpty()) return values
            return listOf(SnapshotValue(did, info?.description ?: "DID %04X".format(did), hex(bytes)))
        }
        fixed[did]?.let { (_, name) -> return listOf(SnapshotValue(did, name, ModuleClient.formatDidValue(bytes))) }
        return listOf(SnapshotValue(did, "DID %04X (manufacturer)".format(did), hex(bytes)))
    }

    private fun hex(b: IntArray) = b.joinToString(" ") { "%02X".format(it) }
}
