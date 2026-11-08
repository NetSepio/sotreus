package app.sotreus.edge.protocol

/** Decodes and encodes 256-byte Edge records (protocol §4). All fields little-endian. */
object EdgeRecordCodec {
    const val SIZE = EdgeProtocol.RECORD_SIZE
    const val HEADER = 28
    const val BODY_MAX = 224
    private const val CRC_AT = 252

    sealed interface Result {
        /** [raw] is the 256-byte record as received, for records kept apart (time not known). */
        class Ok(val record: EdgeRecord, val raw: ByteArray) : Result

        /** CRC mismatch, wrong size or unsupported format. [seq] is read from the header, unverified. */
        data class Corrupt(val reason: String, val seq: Long?) : Result
    }

    fun decode(bytes: ByteArray, offset: Int = 0): Result {
        if (bytes.size - offset < SIZE) return Result.Corrupt("short record: ${bytes.size - offset} bytes", null)
        val r = LeReader(bytes, offset)
        val seq = r.u32(0)
        val crc = r.u32(CRC_AT)
        if (Crc32.of(bytes, offset, CRC_AT) != crc) return Result.Corrupt("CRC mismatch", seq)
        val format = r.u16(26)
        if (format != EdgeProtocol.RECORD_FORMAT) return Result.Corrupt("unsupported format $format", seq)
        val bodyLength = r.u16(24)
        if (bodyLength > BODY_MAX) return Result.Corrupt("body length $bodyLength", seq)
        val header = EdgeRecordHeader(
            seq = seq,
            type = r.u8(4),
            flags = r.u8(5),
            boot = r.u16(6),
            firstMs = r.s64(8),
            lastMs = r.s64(16),
            bodyLength = bodyLength,
            format = format,
        )
        val body = r.bytes(HEADER, BODY_MAX)
        return Result.Ok(EdgeRecord(header, decodeBody(header.type, body)), bytes.copyOfRange(offset, offset + SIZE))
    }

    /**
     * Decodes a record body. Also used for `seen.body` (base64 of the body the open window will be
     * written with). [body] may be shorter than [BODY_MAX]; missing bytes read as zero.
     */
    fun decodeBody(type: Int, body: ByteArray): EdgeRecordBody {
        val b = if (body.size >= BODY_MAX) body else body.copyOf(BODY_MAX)
        val r = LeReader(b)
        return when (type) {
            EdgeRecordType.BLE -> {
                val advLen = r.u8(16).coerceAtMost(31)
                val rspLen = r.u8(17).coerceAtMost(31)
                val flags = r.u8(11)
                BleSighting(
                    address = Mac.format(r.bytes(0, 6)),
                    addressType = r.u8(6),
                    lastPdu = r.u8(7),
                    rssiLast = r.s8(8), rssiMax = r.s8(9), rssiMin = r.s8(10),
                    hasScanResponse = flags and 0x02 != 0,
                    connectable = flags and 0x04 != 0,
                    reports = r.u32(12),
                    advHex = Hex.encode(r.bytes(18, advLen)),
                    scanResponseHex = Hex.encode(r.bytes(49, rspLen)),
                )
            }
            EdgeRecordType.WIFI_AP -> {
                val flags = r.u8(11)
                val ssidLen = r.u8(18).coerceAtMost(32)
                val ridLen = r.u8(51).coerceAtMost(120)
                val ssidBytes = r.bytes(19, ssidLen)
                WifiApSighting(
                    address = Mac.format(r.bytes(0, 6)),
                    channel = r.u8(6),
                    rssiLast = r.s8(7), rssiMax = r.s8(8), rssiMin = r.s8(9),
                    auth = WifiAuth.of(r.u8(10)),
                    hidden = flags and 0x01 != 0 || ssidBytes.all { it.toInt() == 0 },
                    probeResponse = flags and 0x02 != 0,
                    hasRemoteId = flags and 0x04 != 0,
                    scanListOnly = flags and 0x08 != 0,
                    frames = r.u32(12),
                    beaconIntervalTu = r.u16(16),
                    ssid = if (ssidBytes.all { it.toInt() == 0 }) "" else ssidBytes.decodeToString(),
                    remoteIdHex = Hex.encode(r.bytes(52, ridLen)),
                )
            }
            EdgeRecordType.WIFI_PROBE -> {
                val flags = r.u8(10)
                val count = r.u8(11).coerceAtMost(4)
                val inClear = flags and 0x04 != 0
                WifiProbeSighting(
                    address = Mac.format(r.bytes(0, 6)),
                    channel = r.u8(6),
                    rssiLast = r.s8(7), rssiMax = r.s8(8), rssiMin = r.s8(9),
                    randomisedMac = flags and 0x01 != 0,
                    wildcardSeen = flags and 0x02 != 0,
                    namesInClear = inClear,
                    moreNames = flags and 0x08 != 0,
                    frames = r.u32(12),
                    nameHashes = (0 until count).map { r.u32(16 + 4 * it) },
                    names = if (!inClear) emptyList() else (0 until count).map {
                        val at = 32 + 33 * it
                        r.bytes(at + 1, r.u8(at).coerceAtMost(32)).decodeToString()
                    },
                )
            }
            EdgeRecordType.MESH_TEXT -> {
                val hops = r.u8(13)
                MeshTextRecord(
                    from = r.u32(0), to = r.u32(4), packetId = r.u32(8),
                    channelIndex = r.u8(12),
                    hopLimit = hops and 0x0F, hopStart = hops ushr 4,
                    rssi = r.s8(14), snr = r.s8(15) / 4f,
                    sent = r.u8(16) == 1,
                    text = r.bytes(18, r.u8(17).coerceAtMost(206)).decodeToString(),
                )
            }
            EdgeRecordType.EDGE_EVENT -> EdgeEventRecord(r.u16(0), r.bytes(3, r.u8(2).coerceAtMost(221)).decodeToString())
            EdgeRecordType.POSITION -> {
                val alt = r.s32(8)
                val hAcc = r.u16(12)
                val vAcc = r.u16(14)
                val speed = r.u16(18)
                EdgePositionRecord(
                    lat = r.s32(0) / 1e7,
                    lon = r.s32(4) / 1e7,
                    altitudeM = if (alt == Int.MIN_VALUE) null else alt / 1000.0,
                    horizontalAccuracyM = if (hAcc == 0xFFFF) null else hAcc / 10f,
                    verticalAccuracyM = if (vAcc == 0xFFFF) null else vAcc / 10f,
                    satellitesUsed = r.u8(16),
                    fixType = r.u8(17),
                    speedMps = if (speed == 0xFFFF) null else speed / 100f,
                )
            }
            else -> UnknownRecordBody(type, Hex.encode(body))
        }
    }

    /** Builds a full record with a valid CRC. For tests and the fake Edge; the app never writes records. */
    fun encode(header: EdgeRecordHeader, body: ByteArray): ByteArray {
        require(body.size <= BODY_MAX) { "body too long" }
        val out = ByteArray(SIZE)
        val w = LeWriter(out)
        w.u32(0, header.seq)
        w.u8(4, header.type)
        w.u8(5, header.flags)
        w.u16(6, header.boot)
        w.s64(8, header.firstMs)
        w.s64(16, header.lastMs)
        w.u16(24, body.size)
        w.u16(26, header.format)
        w.bytes(HEADER, body)
        w.u32(CRC_AT, Crc32.of(out, 0, CRC_AT))
        return out
    }
}

/** Builders for record bodies, matching the firmware layouts. For tests and the fake Edge. */
object EdgeBodies {
    fun ble(
        address: String, addressType: Int = 1, lastPdu: Int = 0,
        rssiLast: Int, rssiMax: Int = rssiLast, rssiMin: Int = rssiLast,
        scanResponse: Boolean = false, connectable: Boolean = true, reports: Long = 1,
        adv: ByteArray = ByteArray(0), rsp: ByteArray = ByteArray(0),
    ): ByteArray {
        val b = ByteArray(80)
        val w = LeWriter(b)
        w.bytes(0, requireNotNull(Mac.parse(address)))
        w.u8(6, addressType); w.u8(7, lastPdu)
        w.u8(8, rssiLast); w.u8(9, rssiMax); w.u8(10, rssiMin)
        w.u8(11, (if (scanResponse) 0x02 else 0) or (if (connectable) 0x04 else 0))
        w.u32(12, reports)
        w.u8(16, adv.size.coerceAtMost(31)); w.u8(17, rsp.size.coerceAtMost(31))
        w.bytes(18, adv.copyOf(adv.size.coerceAtMost(31)))
        w.bytes(49, rsp.copyOf(rsp.size.coerceAtMost(31)))
        return b
    }

    fun ap(
        bssid: String, channel: Int, rssiLast: Int, rssiMax: Int = rssiLast, rssiMin: Int = rssiLast,
        auth: WifiAuth = WifiAuth.WPA2, hidden: Boolean = false, frames: Long = 10, beaconIntervalTu: Int = 100,
        ssid: String = "", remoteId: ByteArray = ByteArray(0), scanListOnly: Boolean = false,
    ): ByteArray {
        val b = ByteArray(172)
        val w = LeWriter(b)
        val name = ssid.encodeToByteArray().copyOf(ssid.encodeToByteArray().size.coerceAtMost(32))
        w.bytes(0, requireNotNull(Mac.parse(bssid)))
        w.u8(6, channel); w.u8(7, rssiLast); w.u8(8, rssiMax); w.u8(9, rssiMin)
        w.u8(10, auth.code)
        w.u8(11, (if (hidden) 0x01 else 0) or (if (remoteId.isNotEmpty()) 0x04 else 0) or (if (scanListOnly) 0x08 else 0))
        w.u32(12, frames); w.u16(16, beaconIntervalTu)
        w.u8(18, name.size); w.bytes(19, name)
        val rid = remoteId.copyOf(remoteId.size.coerceAtMost(120))
        w.u8(51, rid.size); w.bytes(52, rid)
        return b
    }

    fun probe(
        mac: String, channel: Int, rssiLast: Int, rssiMax: Int = rssiLast, rssiMin: Int = rssiLast,
        randomised: Boolean = true, wildcard: Boolean = false, frames: Long = 3,
        hashes: List<Long> = emptyList(), clearNames: List<String> = emptyList(),
    ): ByteArray {
        val b = ByteArray(164)
        val w = LeWriter(b)
        val n = hashes.size.coerceAtMost(4)
        w.bytes(0, requireNotNull(Mac.parse(mac)))
        w.u8(6, channel); w.u8(7, rssiLast); w.u8(8, rssiMax); w.u8(9, rssiMin)
        w.u8(10, (if (randomised) 0x01 else 0) or (if (wildcard) 0x02 else 0) or (if (clearNames.isNotEmpty()) 0x04 else 0))
        w.u8(11, n)
        w.u32(12, frames)
        hashes.take(4).forEachIndexed { i, h -> w.u32(16 + 4 * i, h) }
        clearNames.take(4).forEachIndexed { i, s ->
            val bytes = s.encodeToByteArray().let { it.copyOf(it.size.coerceAtMost(32)) }
            w.u8(32 + 33 * i, bytes.size); w.bytes(33 + 33 * i, bytes)
        }
        return b
    }

    fun event(code: Int, detail: String): ByteArray {
        val d = detail.encodeToByteArray().let { it.copyOf(it.size.coerceAtMost(221)) }
        val b = ByteArray(3 + d.size)
        val w = LeWriter(b)
        w.u16(0, code); w.u8(2, d.size); w.bytes(3, d)
        return b
    }

    fun position(
        lat: Double, lon: Double, altitudeM: Double?, hAccM: Float?, vAccM: Float?,
        satellites: Int, fixType: Int, speedMps: Float?,
    ): ByteArray {
        val b = ByteArray(20)
        val w = LeWriter(b)
        w.u32(0, Math.round(lat * 1e7) and 0xFFFFFFFFL)
        w.u32(4, Math.round(lon * 1e7) and 0xFFFFFFFFL)
        w.u32(8, (altitudeM?.let { Math.round(it * 1000) } ?: Int.MIN_VALUE.toLong()) and 0xFFFFFFFFL)
        w.u16(12, hAccM?.let { Math.round(it * 10) } ?: 0xFFFF)
        w.u16(14, vAccM?.let { Math.round(it * 10) } ?: 0xFFFF)
        w.u8(16, satellites); w.u8(17, fixType)
        w.u16(18, speedMps?.let { Math.round(it * 100) } ?: 0xFFFF)
        return b
    }
}
