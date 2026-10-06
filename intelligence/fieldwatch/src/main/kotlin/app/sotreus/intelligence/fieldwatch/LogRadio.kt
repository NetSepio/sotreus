/*
 * Ported from Fieldwatch (https://github.com/OffGridPete/Fieldwatch, commit e71b9b2), LogReplay.kt.
 * Copyright (c) 2026 Off Grid Pete LLC. MIT License; see licenses/MIT-Fieldwatch.txt.
 * Modified for Sotreus: only the LogRadio model is kept (log parsing used org.json and is not needed).
 */
package app.sotreus.intelligence.fieldwatch

data class LogRadio(
    val kind: RadioKind,
    val mac: String,
    val name: String,
    val vendor: String?,
    val manufacturerId: Int?,
    val manufacturerDataHex: String,
    val serviceUuids: List<String>,
    val vendorIeOuis: List<String>,
    val randomized: Boolean,
    val hiddenSsid: Boolean,
    val rssi: Int,
    val firstSeen: Long,
    val lastSeen: Long,
    val hits: Int,
    val channel: Int = 0,
    val frequencyMhz: Int = 0,
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    val key: String get() = "${kind.name}:$mac"
    val hasPosition: Boolean get() = latitude != null && longitude != null
}
