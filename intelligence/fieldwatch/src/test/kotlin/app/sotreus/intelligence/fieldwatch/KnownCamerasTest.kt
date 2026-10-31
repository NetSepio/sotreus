/*
 * Ported from Fieldwatch (https://github.com/OffGridPete/Fieldwatch, commit 4f74cec), FlockCamerasTest
 * and PathCamerasTest. Copyright (c) 2026 Off Grid Pete LLC. MIT License; see licenses/MIT-Fieldwatch.txt.
 * Modified for Sotreus: file-based FlockStore API; spoken-alert, canvas and report-text tests not ported.
 */
package app.sotreus.intelligence.fieldwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.util.zip.GZIPOutputStream
import kotlin.math.cos

class KnownCamerasTest {
    @Test
    fun parseKeepsEveryPlateReaderAndDropsBadPoints() {
        val parsed = parseFlock(StringReader(FIXTURE))
        assertEquals(listOf(10L, 11L, -12L, 13L), parsed.map { it.key })
        assertEquals(listOf(90), parsed.first { it.key == 10L }.facings)
        assertTrue(parsed.first { it.key == 10L }.flock)
        assertEquals(SensorKind.PLATE, parsed.first { it.key == 11L }.kind)
        val vigilant = parsed.first { it.key == 11L }
        assertEquals("Vigilant", vigilant.brand)
        assertFalse(vigilant.flock)
        val way = parsed.first { it.key == -12L }
        assertEquals(listOf(0, 180, 270, 45), way.facings)
        assertTrue(way.flock)
        assertEquals("flock safety", way.brand)
        assertEquals(39.74, parsed.first().lat, 0.000001)
    }

    @Test
    fun truncatedJsonThrows() {
        var threw = false
        try {
            parseFlock(StringReader("""{"type":"FeatureCollection","features":["""))
        } catch (_: FlockParseException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun gzipBytesParseTheSameCameras() {
        val zipped = java.io.ByteArrayOutputStream().use { sink ->
            GZIPOutputStream(sink).use { it.write(FIXTURE.toByteArray()) }
            sink.toByteArray()
        }
        assertEquals(parseFlock(StringReader(FIXTURE)).map { it.key }, FlockStore.parse(zipped.inputStream()).map { it.key })
        val dest = File(tempDir(), "plates.bin")
        assertFalse("four cameras are too few to install", FlockStore.commitParsed(dest, zipped, "etag", 10L))
        assertFalse(dest.exists())
    }

    @Test
    fun compactFileRoundTripsAndAShortParseDoesNotReplaceIt() {
        val dest = File(tempDir(), "plates.bin")
        val cameras = List(FlockApproach.MIN_COUNT) { i ->
            FlockCamera(
                key = i + 1L,
                lat = 25.0 + (i % 200) * 0.01,
                lon = -120.0 + (i / 200) * 0.01,
                facings = when {
                    i % 5 == 0 -> listOf(90, 270)
                    i == 3 -> listOf(12)
                    else -> emptyList()
                },
                brand = if (i == 0) "Flock" else if (i == 1) "Vigilant" else "",
                flock = i == 0,
                kind = when (i) {
                    2 -> SensorKind.SPEED
                    3 -> SensorKind.GUNSHOT
                    else -> SensorKind.PLATE
                },
            )
        }
        assertTrue(FlockStore.install(dest, cameras, "\"abc\"", 1_700_000_000_000L))
        val saved = FlockStore.read(dest)!!
        assertEquals(FlockApproach.MIN_COUNT, saved.cameras.size)
        assertEquals(cameras.first().facings, saved.cameras.first().facings)
        assertEquals("\"abc\"", saved.header.etag)
        assertEquals(FlockStore.FORMAT, saved.header.version)
        assertEquals("Vigilant", saved.cameras.first { it.key == 2L }.brand)
        assertEquals(SensorKind.SPEED, saved.cameras.first { it.key == 3L }.kind)
        assertEquals(SensorKind.GUNSHOT, saved.cameras.first { it.key == 4L }.kind)
        assertTrue("a gunshot detector stores no direction", saved.cameras.first { it.key == 4L }.facings.isEmpty())
        val before = dest.readBytes()
        assertFalse(FlockStore.commitParsed(dest, """{"type":"FeatureCollection","features":[]}""".toByteArray(), "new", 9L))
        assertFalse(FlockStore.commitParsed(dest, byteArrayOf('{'.code.toByte()), "new", 9L))
        assertTrue(before.contentEquals(dest.readBytes()))
        assertFalse(FlockStore.install(dest, cameras.take(3), "nope", 2L))
        assertEquals("\"abc\"", FlockStore.peek(dest)!!.etag)
    }

    @Test
    fun tagsPickTheKindAndAWordBearingIsIgnored() {
        val parsed = parseFlock(StringReader(KINDS))
        val speed = parsed.first { it.key == 20L }
        assertEquals(SensorKind.SPEED, speed.kind)
        assertEquals(listOf(90, 180), speed.facings)
        assertEquals(SensorKind.RED, parsed.first { it.key == sensorKey(21L, "relation") }.kind)
        val gun = parsed.first { it.key == 22L }
        assertEquals(SensorKind.GUNSHOT, gun.kind)
        assertTrue(gun.facings.isEmpty())
        assertEquals(SensorKind.CAMERA, parsed.first { it.key == 23L }.kind)
        assertEquals(SensorKind.SPEED, parsed.first { it.key == 24L }.kind)
        assertEquals(SensorKind.PLATE, parsed.first { it.key == 25L }.kind)
        assertEquals(SensorKind.CAMERA, parsed.first { it.key == 27L }.kind)
        assertTrue(parsed.none { it.key == 26L })
        assertTrue(parsed.first { it.key == 28L }.facings.isEmpty())
        assertEquals(SensorKind.RED, parsed.first { it.key == 29L }.kind)
    }

    @Test
    fun aBrokenCameraFileReadsAsNothing() {
        val dest = File(tempDir(), "plates.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)) }
        assertNull(FlockStore.peek(dest))
        assertNull(FlockStore.read(dest))
    }

    @Test
    fun aBadDownloadDoesNotReplaceAGoodFile() {
        val dest = File(tempDir(), "plates.bin")
        assertTrue(FlockStore.commitParsed(dest, platesJson(FlockApproach.MIN_COUNT).toByteArray(), "e", 3L))
        val before = dest.readBytes()
        assertFalse(FlockStore.commitParsed(dest, byteArrayOf(1, 2, 3), "e2", 4L))
        assertFalse(FlockStore.commitParsed(dest, platesJson(3).toByteArray(), "short", 5L))
        assertEquals("e", FlockStore.peek(dest)!!.etag)
        assertTrue(before.contentEquals(dest.readBytes()))
    }

    @Test
    fun anOlderPlateFileStillReads() {
        val dest = File(tempDir(), "plates.bin")
        DataOutputStream(FileOutputStream(dest)).use { out ->
            out.write(byteArrayOf('F'.code.toByte(), 'W'.code.toByte(), 'F'.code.toByte(), 'C'.code.toByte()))
            out.writeShort(2)
            out.writeInt(FlockApproach.MIN_COUNT)
            out.writeLong(5L)
            val etag = "v2".toByteArray()
            out.writeShort(etag.size)
            out.write(etag)
            repeat(FlockApproach.MIN_COUNT) { i ->
                out.writeLong(i + 1L)
                out.writeInt(30_000_000)
                out.writeInt(-100_000_000)
                out.writeByte(if (i == 0) 1 else 0)
                val brand = if (i == 1) "Vigilant".toByteArray() else ByteArray(0)
                out.writeByte(brand.size)
                out.write(brand)
                out.writeByte(0)
            }
        }
        val saved = FlockStore.read(dest)!!
        assertEquals(2, saved.header.version)
        assertEquals(SensorKind.PLATE, saved.cameras.first().kind)
        assertTrue(saved.cameras.first().flock)
        assertEquals("Vigilant", saved.cameras.first { it.key == 2L }.brand)
    }

    @Test
    fun indexFindsCamerasInABoxAndWithinARadius() {
        val lat = 28.78
        val lon = -81.37
        val index = CameraIndex(
            listOf(
                camera(1L, lat, lon, emptyList()),
                camera(2L, north(lat, 100.0), lon, emptyList()),
                camera(3L, north(lat, 2_000.0), lon, emptyList()),
                camera(4L, lat, east(lat, lon, 5_000.0), emptyList()),
            ),
        )
        assertEquals(listOf(1L, 2L), index.near(lat, lon, 150.0).map { it.key }.sorted())
        assertEquals(listOf(1L, 2L, 3L), index.inBox(lat - 0.01, lon - 0.01, lat + 0.03, lon + 0.01).map { it.key }.sorted())
        assertEquals(2, index.inBox(lat - 1, lon - 1, lat + 1, lon + 1, cap = 2).size)
        assertTrue(CameraIndex.EMPTY.near(lat, lon, 500.0).isEmpty())
    }

    @Test
    fun aDirectionTowardThePathCountsAndOneThatPointsAwayDoesNot() {
        val lat = 28.78
        val lon = -81.37
        val path = listOf(GpsSample(1_000L, lat, lon), GpsSample(5_000L, north(lat, 80.0), lon))
        val flock = camera(1L, north(lat, 40.0), east(lat, lon, 15.0), listOf(270), flock = true)
        val plain = camera(2L, north(lat, 40.0), east(lat, lon, -12.0), emptyList())
        val away = camera(3L, north(lat, 40.0), east(lat, lon, 12.0), listOf(0))
        val gun = camera(4L, lat, lon, emptyList(), kind = SensorKind.GUNSHOT)
        val far = camera(5L, north(lat, 400.0), lon, listOf(180), flock = true)
        val speed = camera(6L, north(lat, 40.0), east(lat, lon, -20.0), listOf(90), kind = SensorKind.SPEED)
        val index = CameraIndex(listOf(flock, plain, away, gun, far, speed))
        val tally = PathCameras.tally(path, index::near)
        assertEquals(
            listOf(
                PathCameras.Count(CameraRow.FLOCK, 1, 0),
                PathCameras.Count(CameraRow.PLATE, 0, 1),
                PathCameras.Count(CameraRow.SPEED, 1, 0),
            ),
            tally.counts,
        )
        assertEquals(listOf(1L, 2L, 6L), tally.cameras.map { it.key })
    }

    @Test
    fun aCameraBesideTheMiddleOfAStepStillCountsAndNoPathSaysSo() {
        val lat = 28.78
        val lon = -81.37
        val path = listOf(GpsSample(1_000L, lat, lon), GpsSample(3_000L, north(lat, 40.0), lon))
        val beside = camera(7L, north(lat, 20.0), east(lat, lon, 149.0), emptyList(), flock = true)
        val tally = PathCameras.tally(path, CameraIndex(listOf(beside))::near)
        assertEquals(1, tally.counts.single().near)
        assertFalse(PathCameras.tally(emptyList(), CameraIndex(listOf(beside))::near).pathReady)
    }

    private fun tempDir(): File = File.createTempFile("cameras", "").apply {
        delete()
        mkdirs()
        deleteOnExit()
    }

    private fun platesJson(count: Int): String {
        val features = (1..count).joinToString(",") { i ->
            val lon = -100.0 + i * 0.0001
            """{"type":"Feature","geometry":{"type":"Point","coordinates":[$lon,30.0]},"properties":{"osmId":$i,"osmType":"node","brand":"Flock"}}"""
        }
        return """{"type":"FeatureCollection","features":[$features]}"""
    }

    private fun camera(key: Long, lat: Double, lon: Double, facings: List<Int>, flock: Boolean = false, kind: SensorKind = SensorKind.PLATE) =
        FlockCamera(key, lat, lon, facings, flock = flock, kind = kind)

    private fun north(lat: Double, meters: Double) = lat + meters / 111_320.0

    private fun east(lat: Double, lon: Double, meters: Double): Double = lon + meters / (111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.2))

    private companion object {
        val FIXTURE = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"Point","coordinates":[-104.99,39.74]},
               "properties":{"osmId":10,"osmType":"node","brand":"Flock","operator":"Flock Safety","direction":90}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[-104.98,39.75]},
               "properties":{"osmId":11,"osmType":"node","brand":"Vigilant","operator":"Motorola"}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[-104.97,39.76]},
               "properties":{"osmId":12,"osmType":"way","operator":"flock safety","directions":[0,180,270,45,10]}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[-104.96,39.77]},
               "properties":{"osmId":13,"osmType":"node","brand":"Flock Safety"}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[999,39.7]},
               "properties":{"osmId":14,"osmType":"node","brand":"Flock"}},
              {"type":"Feature","geometry":{"type":"LineString","coordinates":[[0,0],[1,1]]},
               "properties":{"osmId":15,"osmType":"node","brand":"Flock"}},
              {"type":"Feature","geometry":null,"properties":{"osmId":16,"brand":"Flock"}}
            ]}
        """.trimIndent()

        val KINDS = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"Point","coordinates":[-104.1,39.1]},
               "properties":{"osmId":20,"osmType":"node","highway":"speed_camera","direction":"90;180"}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[-104.2,39.2]},
               "properties":{"osmId":21,"osmType":"relation","enforcement":"red_light_camera"}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[-104.3,39.3]},
               "properties":{"osmId":22,"osmType":"node","surveillance:type":"gunshot_detector","direction":"40"}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[-104.4,39.4]},
               "properties":{"osmId":23,"osmType":"node","surveillance:type":"camera","camera:direction":"270"}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[-104.5,39.5]},
               "properties":{"osmId":24,"osmType":"node","surveillance:type":"camera;radar"}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[-104.6,39.6]},
               "properties":{"osmId":25,"osmType":"node","surveillance:type":"ALPR","brand":"Flock"}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[-104.7,39.7]},
               "properties":{"osmId":26,"osmType":"node","enforcement":"stop"}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[-104.8,39.8]},
               "properties":{"osmId":27,"osmType":"node","kind":"camera","highway":"speed_camera"}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[-104.9,39.9]},
               "properties":{"osmId":28,"osmType":"node","direction":"NW","brand":"Flock"}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[-105.0,40.0]},
               "properties":{"osmId":29,"osmType":"node","highway":"speed_camera","enforcement":"red_light_camera","direction":"260"}}
            ]}
        """.trimIndent()
    }
}
