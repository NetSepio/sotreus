package app.sotreus.edge.protocol

/**
 * One 256-byte Edge record (protocol §4). `(deviceId, seq)` is its stable ID. Times are Unix ms
 * when [EdgeRecordHeader.timeValid], otherwise ms since boot number [EdgeRecordHeader.boot].
 */
data class EdgeRecord(val header: EdgeRecordHeader, val body: EdgeRecordBody)

data class EdgeRecordHeader(
    val seq: Long,
    val type: Int,
    val flags: Int,
    /** Low 16 bits of the Edge's boot counter. */
    val boot: Int,
    val firstMs: Long,
    val lastMs: Long,
    val bodyLength: Int,
    val format: Int,
) {
    val timeValid: Boolean get() = flags and FLAG_TIME_VALID != 0

    /** The device was still heard when this window closed; the next window follows. */
    val continues: Boolean get() = flags and FLAG_CONTINUES != 0
    val truncated: Boolean get() = flags and FLAG_TRUNCATED != 0

    companion object {
        const val FLAG_TIME_VALID = 0x01
        const val FLAG_CONTINUES = 0x02
        const val FLAG_TRUNCATED = 0x04
    }
}

object EdgeRecordType {
    const val BLE = 1
    const val WIFI_AP = 2
    const val WIFI_PROBE = 3
    const val MESH_TEXT = 4
    const val EDGE_EVENT = 5

    /** Proposed for Edges with GNSS (`caps.gnss`); not written by any firmware yet. */
    const val POSITION = 6
}

sealed interface EdgeRecordBody

/** A sighting body (types 1–3): one device over one window. RSSI is proximity evidence only. */
sealed interface SightingBody : EdgeRecordBody {
    /** BLE address, BSSID or probing MAC, display order, upper case. */
    val address: String
    val rssiLast: Int
    val rssiMax: Int
    val rssiMin: Int
}

/** Type 1. Raw AD structures go to the phone's signature catalog; the Edge does not classify. */
data class BleSighting(
    override val address: String,
    /** 0 public, 1 random. */
    val addressType: Int,
    /** 0 ADV_IND, 1 ADV_DIRECT_IND, 2 ADV_SCAN_IND, 3 ADV_NONCONN_IND. */
    val lastPdu: Int,
    override val rssiLast: Int,
    override val rssiMax: Int,
    override val rssiMin: Int,
    val hasScanResponse: Boolean,
    val connectable: Boolean,
    val reports: Long,
    val advHex: String,
    val scanResponseHex: String,
) : SightingBody {
    val randomAddress: Boolean get() = addressType == 1
}

enum class WifiAuth(val code: Int) {
    OPEN(0), WEP(1), WPA(2), WPA2(3), WPA3(4), WPA2_WPA3(5), ENTERPRISE(6), OWE(7), UNKNOWN(255);

    companion object {
        fun of(code: Int): WifiAuth = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** Type 2. */
data class WifiApSighting(
    override val address: String,
    val channel: Int,
    override val rssiLast: Int,
    override val rssiMax: Int,
    override val rssiMin: Int,
    val auth: WifiAuth,
    val hidden: Boolean,
    val probeResponse: Boolean,
    val hasRemoteId: Boolean,
    val scanListOnly: Boolean,
    val frames: Long,
    val beaconIntervalTu: Int,
    val ssid: String,
    /** Remote ID vendor element from OUI `FA:0B:BC` onward, possibly truncated. */
    val remoteIdHex: String,
) : SightingBody

/** Type 3. A device asking for networks. Names are salted hashes unless the owner chose clear names. */
data class WifiProbeSighting(
    override val address: String,
    val channel: Int,
    override val rssiLast: Int,
    override val rssiMax: Int,
    override val rssiMin: Int,
    val randomisedMac: Boolean,
    val wildcardSeen: Boolean,
    val namesInClear: Boolean,
    val moreNames: Boolean,
    val frames: Long,
    /** FNV-1a-32 of `probe_salt ‖ SSID`, one per distinct probed name (≤ 4). */
    val nameHashes: List<Long>,
    /** Only when `awareness.probe_names` was on; otherwise empty. */
    val names: List<String>,
) : SightingBody

/** Type 4. Not ingested on `edge-client` (mesh UI is a later branch); decoded so the cursor can pass. */
data class MeshTextRecord(
    val from: Long,
    val to: Long,
    val packetId: Long,
    val channelIndex: Int,
    val hopLimit: Int,
    val hopStart: Int,
    val rssi: Int,
    val snr: Float,
    val sent: Boolean,
    val text: String,
) : EdgeRecordBody

/** Type 5: boot, mode, time set, log erased, config, firmware. */
data class EdgeEventRecord(val code: Int, val detail: String) : EdgeRecordBody {
    companion object {
        const val BOOT = 1
        const val MODE = 2
        const val TIME_SET = 3
        const val LOG_ERASED = 4
        const val CONFIG = 5
        const val FIRMWARE = 6
    }
}

/**
 * Type 6 (proposed, `caps.gnss`): the Edge's own position fix at [EdgeRecordHeader.firstMs].
 * Layout: lat e7 (s32), lon e7 (s32), altitude WGS84 mm (s32), horizontal accuracy dm (u16),
 * vertical accuracy dm (u16), satellites used (u8), fix type 0/2/3 (u8), speed cm/s (u16).
 * Accuracy and speed of 0xFFFF mean unknown.
 */
data class EdgePositionRecord(
    val lat: Double,
    val lon: Double,
    val altitudeM: Double?,
    val horizontalAccuracyM: Float?,
    val verticalAccuracyM: Float?,
    val satellitesUsed: Int,
    val fixType: Int,
    val speedMps: Float?,
) : EdgeRecordBody {
    val hasFix: Boolean get() = fixType >= 2
}

data class UnknownRecordBody(val type: Int, val hex: String) : EdgeRecordBody
