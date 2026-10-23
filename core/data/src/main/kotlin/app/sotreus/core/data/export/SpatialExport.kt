package app.sotreus.core.data.export

import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class SpatialFix(val atMs: Long, val lat: Double, val lon: Double)
data class SpatialMarker(val id: String, val name: String, val fix: SpatialFix, val meaning: String, val note: String = "")

/** Inputs have already passed the user's coordinate/identifier/time export choices. */
object SpatialExport {
    fun geoJson(route: List<SpatialFix>, markers: List<SpatialMarker>): String = buildJsonObject {
        put("type", "FeatureCollection")
        put("source", "Sotreus")
        put("note", "Phone path and hear locations are not the locations of other radios. Advertised aircraft positions are labelled separately.")
        putJsonArray("features") {
            if (route.isNotEmpty()) add(buildJsonObject {
                put("type", "Feature")
                putJsonObject("properties") { put("kind", "phone-path"); put("startMs", route.first().atMs); put("endMs", route.last().atMs) }
                putJsonObject("geometry") {
                    put("type", if (route.size == 1) "Point" else "LineString")
                    if (route.size == 1) put("coordinates", coordinate(route.single())) else put("coordinates", JsonArray(route.map(::coordinate)))
                }
            })
            markers.forEach { marker -> add(buildJsonObject {
                put("type", "Feature")
                putJsonObject("properties") {
                    put("id", marker.id); put("name", marker.name); put("atMs", marker.fix.atMs)
                    put("locationMeaning", marker.meaning)
                    if (marker.note.isNotBlank()) put("note", marker.note)
                }
                putJsonObject("geometry") { put("type", "Point"); put("coordinates", coordinate(marker.fix)) }
            }) }
        }
    }.toString()

    /** ATAK Mission Package with a KML path and neutral CoT spot markers. */
    fun atak(route: List<SpatialFix>, markers: List<SpatialMarker>): ByteArray {
        val entries = linkedMapOf<String, String>()
        val kml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>Sotreus phone path</name>")
            if (route.isNotEmpty()) {
                append("<Placemark><name>Phone path</name><description>Where this phone traveled</description>")
                append(if (route.size == 1) "<Point><coordinates>" else "<LineString><tessellate>1</tessellate><coordinates>")
                route.forEach { append("${it.lon},${it.lat},0 ") }
                append(if (route.size == 1) "</coordinates></Point></Placemark>" else "</coordinates></LineString></Placemark>")
            }
            append("</Document></kml>")
        }
        entries["path/phone.kml"] = kml
        markers.forEachIndexed { index, m ->
            val time = Instant.ofEpochMilli(m.fix.atMs).toString()
            val stale = Instant.ofEpochMilli(m.fix.atMs + 24 * 60 * 60_000).toString()
            entries["markers/$index.cot"] = """<?xml version="1.0" encoding="UTF-8"?><event version="2.0" uid="${xml("sotreus-${m.id}-$index")}" type="b-m-p-s-m" time="$time" start="$time" stale="$stale" how="h-g-i-g-o"><point lat="${m.fix.lat}" lon="${m.fix.lon}" hae="0" ce="9999999" le="9999999"/><detail><contact callsign="${xml(m.name)}"/><remarks>${xml(m.meaning + if (m.note.isBlank()) "" else ". ${m.note}")}</remarks></detail></event>"""
        }
        val manifest = """<?xml version="1.0" encoding="UTF-8"?><MissionPackageManifest version="2"><Configuration><Parameter name="uid" value="${UUID.randomUUID()}"/><Parameter name="name" value="Sotreus observations"/></Configuration><Contents>${entries.keys.joinToString("") { "<Content ignore=\"false\" zipEntry=\"$it\"/>" }}</Contents></MissionPackageManifest>"""
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                (linkedMapOf("MANIFEST/manifest.xml" to manifest) + entries).forEach { (name, text) ->
                    zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
                }
            }
            output.toByteArray()
        }
    }

    private fun coordinate(fix: SpatialFix) = JsonArray(listOf(JsonPrimitive(fix.lon), JsonPrimitive(fix.lat)))
    private fun xml(raw: String) = raw.filter { it >= ' ' || it == '\n' || it == '\t' }.replace("&", "&amp;")
        .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
