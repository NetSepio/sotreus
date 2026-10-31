/*
 * Ported from Fieldwatch (https://github.com/OffGridPete/Fieldwatch, commit 4f74cec).
 * Copyright (c) 2026 Off Grid Pete LLC. MIT License; see licenses/MIT-Fieldwatch.txt.
 * Modified for Sotreus: package renamed; the compact file format of FlockFiles without its
 * HttpURLConnection download, which Sotreus does with OkHttp in core:data.
 */
package app.sotreus.intelligence.fieldwatch

import java.util.concurrent.CancellationException
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/**
 * The saved camera lists. A list is parsed once from GeoJSON (plain or gzip) and written in a compact
 * binary file sorted by latitude. A bad parse never replaces a list that already passed the check.
 */
object FlockStore {
    /** Version 1 is Flock only. Version 2 is every plate reader. Version 3 adds a type byte after the brand. */
    const val FORMAT: Int = 3
    private const val VERSION_FLOCK_ONLY: Short = 1
    private const val MAX_COUNT = 400_000
    private val MAGIC = byteArrayOf('F'.code.toByte(), 'W'.code.toByte(), 'F'.code.toByte(), 'C'.code.toByte())

    data class Header(val count: Int, val savedAt: Long, val etag: String, val version: Int = FORMAT)

    data class Saved(val header: Header, val cameras: List<FlockCamera>)

    /** Parses a downloaded GeoJSON file, unpacking gzip when the bytes are gzip. */
    fun parse(input: InputStream): List<FlockCamera> = reader(input).use { parseFlock(it) }

    fun peek(file: File): Header? {
        if (!file.isFile || file.length() < 20) return null
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { readHeader(it) }
        } catch (_: Exception) {
            null
        }
    }

    fun read(file: File): Saved? = runCatching { readFile(file) }.getOrNull()

    /**
     * Writes [cameras] only when there are at least [FlockApproach.MIN_COUNT].
     * Returns false and leaves any existing file in place otherwise.
     */
    fun install(dest: File, cameras: List<FlockCamera>, etag: String, savedAt: Long): Boolean {
        if (cameras.size < FlockApproach.MIN_COUNT || cameras.size > MAX_COUNT) return false
        val tmp = File(dest.parentFile, "${dest.name}.write")
        return try {
            write(tmp, cameras, etag, savedAt)
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            true
        } catch (e: CancellationException) {
            tmp.delete()
            throw e
        } catch (_: Exception) {
            tmp.delete()
            false
        }
    }

    /** Parse [bytes] and install. A short list, or a parse error, leaves the saved file alone. */
    fun commitParsed(dest: File, bytes: ByteArray, etag: String, savedAt: Long): Boolean {
        val cameras = try {
            parse(bytes.inputStream())
        } catch (_: Exception) {
            return false
        }
        if (cameras.size < FlockApproach.MIN_COUNT) return false
        return install(dest, cameras, etag, savedAt)
    }

    private fun reader(raw: InputStream): InputStreamReader {
        val input = BufferedInputStream(raw)
        input.mark(4)
        val b0 = input.read()
        val b1 = input.read()
        input.reset()
        val stream = if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(input) else input
        return InputStreamReader(stream, Charsets.UTF_8)
    }

    private fun readHeader(input: DataInputStream): Header? {
        val magic = ByteArray(4)
        input.readFully(magic)
        if (!magic.contentEquals(MAGIC)) return null
        val version = input.readShort()
        if (version.toInt() !in 1..FORMAT) return null
        val count = input.readInt()
        if (count < FlockApproach.MIN_COUNT || count > MAX_COUNT) return null
        val savedAt = input.readLong()
        val etagLen = input.readUnsignedShort()
        if (etagLen > 512) return null
        val etagBytes = ByteArray(etagLen)
        input.readFully(etagBytes)
        return Header(count, savedAt, etagBytes.toString(Charsets.UTF_8), version.toInt())
    }

    private fun readFile(file: File): Saved {
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            val header = readHeader(input) ?: error("bad camera file")
            val legacy = header.version == VERSION_FLOCK_ONLY.toInt()
            val cameras = ArrayList<FlockCamera>(header.count)
            repeat(header.count) {
                val key = input.readLong()
                val lat = input.readInt() / 1_000_000.0
                val lon = input.readInt() / 1_000_000.0
                val flock = if (legacy) true else input.readUnsignedByte() == 1
                val brand = if (legacy) {
                    "Flock"
                } else {
                    val len = input.readUnsignedByte()
                    if (len > 48) error("bad camera brand")
                    val bytes = ByteArray(len)
                    if (len > 0) input.readFully(bytes)
                    bytes.toString(Charsets.UTF_8)
                }
                val kind = if (header.version >= FORMAT) {
                    val raw = input.readUnsignedByte()
                    if (raw >= SensorKind.entries.size) error("bad sensor kind")
                    SensorKind.entries[raw]
                } else {
                    SensorKind.PLATE
                }
                val n = input.readUnsignedByte()
                if (n > FlockApproach.MAX_FACINGS) error("bad camera facing")
                val facings = List(n) {
                    val deg = input.readUnsignedShort()
                    if (deg > 359) error("bad camera degree")
                    deg
                }
                cameras.add(FlockCamera(key, lat, lon, facings, brand, flock, kind).forStore())
            }
            return Saved(header, cameras)
        }
    }

    private fun write(file: File, cameras: List<FlockCamera>, etag: String, savedAt: Long) {
        val sorted = cameras.map { it.forStore() }.sortedWith(compareBy({ it.lat }, { it.lon }, { it.key }))
        val etagBytes = etag.encodeToByteArray().let { if (it.size > 512) it.copyOf(512) else it }
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            out.write(MAGIC)
            out.writeShort(FORMAT)
            out.writeInt(sorted.size)
            out.writeLong(savedAt)
            out.writeShort(etagBytes.size)
            out.write(etagBytes)
            for (cam in sorted) {
                out.writeLong(cam.key)
                out.writeInt(micro(cam.lat))
                out.writeInt(micro(cam.lon))
                val faces = cam.facings.take(FlockApproach.MAX_FACINGS)
                val brandBytes = cam.brand.encodeToByteArray().let { if (it.size > 48) it.copyOf(48) else it }
                out.writeByte(if (cam.flock) 1 else 0)
                out.writeByte(brandBytes.size)
                out.write(brandBytes)
                out.writeByte(cam.kind.ordinal)
                out.writeByte(faces.size)
                for (deg in faces) out.writeShort(deg.coerceIn(0, 359))
            }
        }
    }

    private fun micro(deg: Double): Int = kotlin.math.round(deg * 1_000_000.0).toInt()
}
