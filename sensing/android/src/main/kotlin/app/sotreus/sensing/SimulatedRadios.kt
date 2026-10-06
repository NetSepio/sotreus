package app.sotreus.sensing

import app.sotreus.intelligence.fieldwatch.Observation
import app.sotreus.intelligence.fieldwatch.RadioFacts
import app.sotreus.intelligence.fieldwatch.RadioKind
import app.sotreus.intelligence.fieldwatch.ServiceDataRecord
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import javax.inject.Inject

/**
 * A simulated radio environment matching the sample world in the mocks (Grey tag, NS-Office,
 * HP-Print-3C, …). It exists so the app can be exercised on an emulator or without radios. Every
 * simulated address starts with `5A:` ("SA") and the UI labels simulated data as simulated.
 * Wi-Fi results follow Android's cadence and one AP goes quiet, so staleness can be seen.
 * One simulated drone broadcasts ASTM F3411 Remote ID over BLE (service data FFFA), one message
 * type per advertisement as real legacy-advertising broadcasters do, flying a slow circle a few
 * hundred metres from the phone's last known location (or a fixed point without one).
 */
class SimulatedRadios @Inject constructor(private val location: LocationSource) {
    private data class Radio(
        val kind: RadioKind,
        val mac: String,
        val name: String,
        val baseRssi: Int,
        val uuids: List<String> = emptyList(),
        val companyId: Int? = null,
        val mfgHex: String = "",
        val security: String? = null,
        val freq: Int = 2412,
        /** Stops being heard after this many seconds (to show STALE / lost). */
        val quietAfterS: Int? = null,
        val addressType: String = "Random",
        val connectable: Boolean = true,
    )

    private val world: List<Radio> = buildList {
        add(Radio(RadioKind.BLE, "5A:A1:0E:7C:21:4B", "Tile", -58, uuids = listOf("FEED"), addressType = "Random"))
        add(Radio(RadioKind.BLE, "5A:C3:91:44:0B:62", "EufyCam", -67, connectable = false))
        add(Radio(RadioKind.WIFI, "5A:0F:B3:11:22:33", "NS-Office", -49, security = "[WPA2-PSK-CCMP][RSN-SAE-CCMP][ESS]", freq = 2437))
        add(Radio(RadioKind.BLE, "5A:7E:22:90:12:3C", "", -72, uuids = listOf("FE2C")))
        add(Radio(RadioKind.BLE, "5A:4D:13:65:AA:01", "", -81))
        add(Radio(RadioKind.WIFI, "5A:3C:D9:2B:41:7F", "HP-Print-3C", -76, security = "[WPA2-PSK-CCMP][ESS]", freq = 2462, quietAfterS = 1))
        add(Radio(RadioKind.WIFI, "5A:0F:B3:44:55:66", "NS-Guest", -63, security = "[ESS]", freq = 5180))
        add(Radio(RadioKind.BLE, "5A:12:5B:DE:77:10", "Meeting-room display", -66, companyId = 0x0075, mfgHex = "4204"))
        add(Radio(RadioKind.BLE, "5A:12:5B:DE:77:11", "Door access reader", -79, addressType = "Public"))
        add(Radio(RadioKind.BLE, "5A:62:09:3F:CC:20", "My earbuds", -55, uuids = listOf("FE2C"), quietAfterS = 90))
        repeat(20) { i ->
            add(Radio(RadioKind.BLE, "5A:B0:%02X:%02X:10:%02X".format(i, i * 7 % 256, i), "", -70 - (i * 3) % 22, companyId = 0x004C, mfgHex = "1005"))
        }
        repeat(10) { i ->
            add(Radio(RadioKind.WIFI, "5A:E0:%02X:20:30:%02X".format(i, i), "Office-AP-${i + 1}", -60 - (i * 4) % 30, security = "[WPA2-EAP-CCMP][ESS]", freq = if (i % 2 == 0) 2412 + 5 * i else 5180 + 20 * i))
        }
    }

    suspend fun run(
        emitBle: (Observation) -> Unit,
        emitWifiScan: (List<Observation>, fresh: Boolean, rejected: Boolean) -> Unit,
    ) {
        val started = System.currentTimeMillis()
        val fix = location.lastKnown()
        droneBase = if (fix != null) (fix.lat + 0.0035) to (fix.lon - 0.0025) else DRONE_BASE_LAT to DRONE_BASE_LON
        val walk = world.associate { it.mac to 0 }.toMutableMap()
        var tick = 0
        while (coroutineContext.isActive) {
            val now = System.currentTimeMillis()
            val elapsedS = ((now - started) / 1000).toInt()
            world.filter { it.kind == RadioKind.BLE && (it.quietAfterS == null || elapsedS < it.quietAfterS) }.forEach { r ->
                if (Random.nextFloat() < 0.85f) {
                    val drift = (walk.getValue(r.mac) + Random.nextInt(-2, 3)).coerceIn(-8, 8)
                    walk[r.mac] = drift
                    emitBle(observation(r, r.baseRssi + drift, now, fresh = true))
                }
            }
            if (Random.nextFloat() < 0.9f) emitBle(droneObservation(tick, now))
            // Wi-Fi: Android allows ~4 scans per 2 minutes; deliver a fresh batch every 30 s,
            // and every fourth scan is "rejected" so the throttled state can be seen.
            if (tick % 30 == 0) {
                val rejected = (tick / 30) % 4 == 3
                val aps = world.filter { it.kind == RadioKind.WIFI && (it.quietAfterS == null || elapsedS < it.quietAfterS) }
                if (!rejected) emitWifiScan(aps.map { observation(it, it.baseRssi + Random.nextInt(-3, 4), now, fresh = true) }, true, false)
                else emitWifiScan(emptyList(), false, true)
            }
            tick++
            delay(1_000)
        }
    }

    private fun observation(r: Radio, rssi: Int, at: Long, fresh: Boolean) = Observation(
        kind = r.kind,
        mac = r.mac,
        name = r.name,
        rssi = rssi,
        channel = if (r.kind == RadioKind.WIFI) channelOf(r.freq) else 0,
        frequencyMhz = if (r.kind == RadioKind.WIFI) r.freq else 2402,
        hiddenSsid = false,
        serviceUuids = r.uuids,
        manufacturerId = r.companyId,
        manufacturerDataHex = r.mfgHex,
        rawHex = "",
        extras = "",
        at = at,
        fresh = fresh,
        facts = RadioFacts(
            addressType = if (r.kind == RadioKind.BLE) r.addressType else null,
            connectable = if (r.kind == RadioKind.BLE) r.connectable else null,
            capabilities = r.security,
            security = r.security,
            serviceData = r.uuids.map { ServiceDataRecord(it, "") },
        ),
    )

    private var droneBase = DRONE_BASE_LAT to DRONE_BASE_LON

    private fun droneObservation(tick: Int, at: Long): Observation {
        val (baseLat, baseLon) = droneBase
        val angle = (tick % 120) / 120.0 * 2 * Math.PI
        val lat = baseLat + 0.0012 * kotlin.math.sin(angle)
        val lon = baseLon + 0.0015 * kotlin.math.cos(angle)
        val msg = when (tick % 4) {
            0 -> remoteIdBasic(DRONE_UAS_ID)
            1, 3 -> remoteIdLocation(lat, lon, altM = 62.0, courseDeg = (Math.toDegrees(angle) + 90) % 360, speedMps = 6.5)
            else -> remoteIdSystem(baseLat, baseLon)
        }.let { if (tick % 8 == 6) remoteIdSelf("Simulated survey flight") else it }
        return Observation(
            kind = RadioKind.BLE, mac = DRONE_MAC, name = "", rssi = -74 + Random.nextInt(-3, 4), channel = 0, frequencyMhz = 2402,
            hiddenSsid = false, serviceUuids = listOf("FFFA"), manufacturerId = null, manufacturerDataHex = "", rawHex = "", extras = "",
            at = at, fresh = true,
            facts = RadioFacts(
                addressType = "Random", connectable = false,
                serviceData = listOf(ServiceDataRecord("FFFA", "0D%02X".format(tick and 0xFF) + msg.joinToString("") { "%02X".format(it.toInt() and 0xFF) })),
            ),
        )
    }

    private fun channelOf(freq: Int): Int = when {
        freq in 2412..2484 -> (freq - 2407) / 5
        freq >= 5000 -> (freq - 5000) / 5
        else -> 0
    }

    private companion object {
        const val DRONE_MAC = "5A:D0:0E:1D:00:01"
        const val DRONE_UAS_ID = "SIM0SOTREUS000000001"
        const val DRONE_BASE_LAT = 37.4230
        const val DRONE_BASE_LON = -122.0850

        fun le32(n: Int) = byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte())
        fun le16(n: Int) = byteArrayOf(n.toByte(), (n shr 8).toByte())

        /** ASTM F3411 Basic ID: serial number, multirotor. */
        fun remoteIdBasic(id: String): ByteArray =
            byteArrayOf(0x02, 0x12) + id.toByteArray(Charsets.US_ASCII).copyOf(20) + ByteArray(3)

        /** ASTM F3411 Location/Vector. */
        fun remoteIdLocation(lat: Double, lon: Double, altM: Double, courseDeg: Double, speedMps: Double): ByteArray {
            val ew = courseDeg >= 180
            val dir = (if (ew) courseDeg - 180 else courseDeg).toInt()
            val flags = 0x20 or (if (ew) 0x02 else 0)
            val alt = le16(((altM + 1000) * 2).toInt())
            return byteArrayOf(0x12, flags.toByte(), dir.toByte(), (speedMps / 0.25).toInt().toByte(), 0) +
                le32((lat * 1e7).toInt()) + le32((lon * 1e7).toInt()) + alt + alt + alt + ByteArray(6)
        }

        /** ASTM F3411 System: operator location. */
        fun remoteIdSystem(lat: Double, lon: Double): ByteArray =
            byteArrayOf(0x42, 0x01) + le32((lat * 1e7).toInt()) + le32((lon * 1e7).toInt()) + ByteArray(15)

        /** ASTM F3411 Self ID: free-text description. */
        fun remoteIdSelf(text: String): ByteArray =
            byteArrayOf(0x32, 0x00) + text.toByteArray(Charsets.US_ASCII).copyOf(23)
    }
}
