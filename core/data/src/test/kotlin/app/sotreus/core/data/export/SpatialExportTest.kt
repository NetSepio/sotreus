package app.sotreus.core.data.export

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class SpatialExportTest {
    @Test fun reducedExportCoarsensTimeAndUsesPerExportIdentifiers() {
        val p = SpatialPrivacy(ExportOptions.PrivacyReduced)
        val fix = p.fix(1_700_000_123_456, 12.345678, -76.543219)!!
        assertEquals(12.35, fix.lat, 0.0); assertEquals(-76.54, fix.lon, 0.0)
        assertEquals(0L, fix.atMs % 900_000)
        assertEquals(p.id("ble:device"), p.id("ble:device"))
        assertNotEquals("ble:device", p.id("ble:device"))
        assertNotEquals(p.id("ble:device"), SpatialPrivacy(ExportOptions.PrivacyReduced).id("ble:device"))
    }
    @Test fun coordinatesOffAndInvalidPositionsProduceNoGeometry() {
        val off = SpatialPrivacy(ExportOptions(coordinates = ExportOptions.Coordinates.NONE))
        assertNull(off.fix(1, 1.0, 2.0))
        val p = SpatialPrivacy(ExportOptions())
        assertNull(p.fix(1, Double.NaN, 1.0)); assertNull(p.fix(1, 91.0, 1.0)); assertNull(p.fix(1, 1.0, null))
    }
    @Test fun geoJsonUsesLongitudeFirstAndLabelsPhoneHearPositions() {
        val point = SpatialFix(1, 12.0, 77.0)
        val data = Json.parseToJsonElement(SpatialExport.geoJson(listOf(point), listOf(SpatialMarker("safe", "A\"<>&", point, "This phone at radio hear time")))).jsonObject
        val features = data.getValue("features").jsonArray
        assertEquals("Point", features[0].jsonObject.getValue("geometry").jsonObject.getValue("type").jsonPrimitive.content)
        val coordinates = features[1].jsonObject.getValue("geometry").jsonObject.getValue("coordinates").jsonArray
        assertEquals(77.0, coordinates[0].jsonPrimitive.double, 0.0)
        assertEquals("This phone at radio hear time", features[1].jsonObject.getValue("properties").jsonObject.getValue("locationMeaning").jsonPrimitive.content)
    }
    @Test fun atakManifestReferencesEveryEntryAndEscapesRadioText() {
        val f = SpatialFix(1_700_000_000_000, 12.0, 77.0)
        val bytes = SpatialExport.atak(listOf(f, f.copy(atMs = f.atMs + 10_000, lat = 12.01)), listOf(SpatialMarker("../../bad", "A < B & C\"", f, "This phone at hear time")))
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip -> while (true) { val entry = zip.nextEntry ?: break; files[entry.name] = zip.readBytes() } }
        assertEquals(setOf("MANIFEST/manifest.xml", "path/phone.kml", "markers/0.cot"), files.keys)
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        val manifest = factory.newDocumentBuilder().parse(ByteArrayInputStream(files.getValue("MANIFEST/manifest.xml")))
        val contents = manifest.getElementsByTagName("Content")
        assertEquals(2, contents.length)
        for (i in 0 until contents.length) assertTrue(files.containsKey(contents.item(i).attributes.getNamedItem("zipEntry").nodeValue))
        val cot = factory.newDocumentBuilder().parse(ByteArrayInputStream(files.getValue("markers/0.cot")))
        assertEquals("A < B & C\"", cot.getElementsByTagName("contact").item(0).attributes.getNamedItem("callsign").nodeValue)
        assertEquals("b-m-p-s-m", cot.documentElement.getAttribute("type"))
        factory.newDocumentBuilder().parse(ByteArrayInputStream(files.getValue("path/phone.kml")))
    }
    @Test fun onePointRouteProducesValidKmlPoint() {
        val bytes = SpatialExport.atak(listOf(SpatialFix(1, 12.0, 77.0)), emptyList())
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "path/phone.kml") {
                    val kml = zip.readBytes().toString(Charsets.UTF_8)
                    assertTrue(kml.contains("<Point>")); assertFalse(kml.contains("<LineString>"))
                    return
                }
            }
        }
        fail("Missing route KML")
    }
}
