/*
 * Adapted from Fieldwatch DeviceStore.upsert (https://github.com/OffGridPete/Fieldwatch, commit e71b9b2).
 * Copyright (c) 2026 Off Grid Pete LLC. MIT License; see licenses/MIT-Fieldwatch.txt.
 * Modified for Sotreus: keyed by Sotreus entity id; signature relabelling moved to the pipeline;
 * GPS trail and live-decode handling removed.
 */
package app.sotreus.core.data.pipeline

import app.sotreus.intelligence.fieldwatch.FastPair
import app.sotreus.intelligence.fieldwatch.MacUtil
import app.sotreus.intelligence.fieldwatch.Observation
import app.sotreus.intelligence.fieldwatch.OuiLookup
import app.sotreus.intelligence.fieldwatch.PresenceSpan
import app.sotreus.intelligence.fieldwatch.RadioKind
import app.sotreus.intelligence.fieldwatch.Rssi
import app.sotreus.intelligence.fieldwatch.RssiSample
import app.sotreus.intelligence.fieldwatch.Sighting

/** In-memory live set of radios, merged the way Fieldwatch merges them. Not thread-safe. */
internal class LiveSightings {
    private val historyLimit = 40
    val live = LinkedHashMap<String, Sighting>()

    fun upsert(key: String, observation: Observation): Sighting {
        val mac = MacUtil.normalize(observation.mac)
        val now = if (observation.at > 0L) observation.at else System.currentTimeMillis()
        val existing = live[key]
        if (observation.kind == RadioKind.WIFI && !observation.fresh && existing != null) {
            // A cached (not fresh) Wi-Fi result must not refresh freshness.
            val next = existing.copy(
                name = observation.name.ifBlank { existing.name },
                vendorIeOuis = mergeIes(existing.vendorIeOuis, observation.vendorIeOuis),
                facts = existing.facts.merge(observation.facts),
            )
            live[key] = next
            return next
        }
        val measured = Rssi.measured(observation.rssi)
        val sample = if (measured) RssiSample(now, observation.rssi) else null
        val merged = if (existing == null) {
            Sighting(
                key = key,
                kind = observation.kind,
                mac = mac,
                name = observation.name,
                rssi = observation.rssi,
                rssiMin = observation.rssi,
                rssiMax = observation.rssi,
                channel = observation.channel,
                frequencyMhz = observation.frequencyMhz,
                vendor = OuiLookup.vendor(mac),
                randomized = MacUtil.isRandomized(mac),
                hiddenSsid = observation.hiddenSsid,
                serviceUuids = observation.serviceUuids,
                manufacturerId = observation.manufacturerId,
                manufacturerDataHex = observation.manufacturerDataHex.take(512),
                rawHex = observation.rawHex.take(1024),
                extras = observation.extras.take(160),
                firstSeen = now,
                lastSeen = now,
                hitCount = 1,
                fleetIds = emptySet(),
                rssiHistory = listOfNotNull(sample),
                presence = listOf(PresenceSpan(now, null)),
                vendorIeOuis = observation.vendorIeOuis,
                facts = observation.facts,
                fastPairPairing = FastPair.pairingAdvertised(observation.facts),
            )
        } else {
            val history = when {
                sample == null -> existing.rssiHistory
                existing.rssiHistory.size >= historyLimit -> existing.rssiHistory.drop(existing.rssiHistory.size - historyLimit + 1) + sample
                else -> existing.rssiHistory + sample
            }
            val uuids = if (observation.serviceUuids.isEmpty()) existing.serviceUuids else (existing.serviceUuids + observation.serviceUuids).distinct()
            existing.copy(
                name = observation.name.ifBlank { existing.name },
                rssi = if (measured) observation.rssi else existing.rssi,
                vendor = existing.vendor ?: OuiLookup.vendor(mac),
                rssiMin = if (measured) minOf(existing.rssiMin, observation.rssi) else existing.rssiMin,
                rssiMax = if (measured) maxOf(existing.rssiMax, observation.rssi) else existing.rssiMax,
                channel = if (observation.channel != 0) observation.channel else existing.channel,
                frequencyMhz = if (observation.frequencyMhz != 0) observation.frequencyMhz else existing.frequencyMhz,
                hiddenSsid = existing.hiddenSsid || observation.hiddenSsid,
                serviceUuids = uuids,
                manufacturerId = existing.manufacturerId ?: observation.manufacturerId,
                manufacturerDataHex = mergeMfgHex(existing.manufacturerDataHex, observation.manufacturerDataHex),
                rawHex = if (observation.rawHex.length >= existing.rawHex.length) observation.rawHex.take(1024) else existing.rawHex,
                lastSeen = now,
                hitCount = existing.hitCount + 1,
                rssiHistory = history,
                gone = false,
                vendorIeOuis = mergeIes(existing.vendorIeOuis, observation.vendorIeOuis),
                facts = existing.facts.merge(observation.facts),
                fastPairPairing = existing.fastPairPairing || FastPair.pairingAdvertised(observation.facts),
            )
        }
        live[key] = merged
        return merged
    }

    fun evictOlderThan(cutoffMs: Long) {
        live.entries.removeAll { it.value.lastSeen < cutoffMs }
    }

    private fun mergeMfgHex(old: String, extra: String): String {
        if (extra.isBlank()) return old
        if (old.isBlank()) return extra.take(512)
        if (extra.take(2).equals(old.take(2), ignoreCase = true) && extra.length >= old.length) return extra.take(512)
        return old
    }

    private fun mergeIes(old: List<String>, extra: List<String>): List<String> =
        if (extra.isEmpty()) old else if (old.isEmpty()) extra else (old + extra).distinct()
}
