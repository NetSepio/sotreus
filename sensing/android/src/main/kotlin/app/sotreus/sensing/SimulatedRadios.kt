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
 */
class SimulatedRadios @Inject constructor() {
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

    private fun channelOf(freq: Int): Int = when {
        freq in 2412..2484 -> (freq - 2407) / 5
        freq >= 5000 -> (freq - 5000) / 5
        else -> 0
    }
}
