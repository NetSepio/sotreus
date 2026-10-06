package app.sotreus.intelligence

import app.sotreus.core.model.Confidence
import app.sotreus.core.model.DeviceFamily
import app.sotreus.intelligence.fieldwatch.DefaultCatalog
import app.sotreus.intelligence.fieldwatch.DeviceExplain
import app.sotreus.intelligence.fieldwatch.Fleet
import app.sotreus.intelligence.fieldwatch.SignatureClass
import app.sotreus.intelligence.fieldwatch.SignatureEngine
import app.sotreus.intelligence.fieldwatch.Sighting

/** What the radio's advertisement suggests. An observation, never an identity. */
data class Classification(
    val family: DeviceFamily?,
    /** Vendor/product row from the signature catalog, e.g. "Tile Trackers". */
    val signatureName: String?,
    val signatureIds: Set<String>,
    /** Fieldwatch's plain-language guess, e.g. "Most likely a finder tag". */
    val guess: String,
    val guessConfidence: Confidence,
    /** The catalog marks this family as worth a look (Fieldwatch "extra attention"). */
    val notable: Boolean,
)

/**
 * Wraps Fieldwatch's field-tested [SignatureEngine] and [DeviceExplain] with Sotreus families.
 * Catalog notes are not surfaced: Sotreus writes its own calm copy.
 */
class SignatureClassifier(fleets: List<Fleet> = DefaultCatalog.fleets()) {
    val fleets: List<Fleet> = fleets.filter { it.enabled }
    private val byId = this.fleets.associateBy { it.id }
    private val engine = SignatureEngine()

    val catalogSize: Int get() = fleets.size

    fun classify(sightings: Collection<Sighting>, now: Long): Map<String, Classification> {
        val hits = engine.match(sightings, fleets, now)
        return sightings.associate { s -> s.key to classifyOne(s, hits[s.key].orEmpty()) }
    }

    private fun classifyOne(sighting: Sighting, ids: Set<String>): Classification {
        val matched = ids.mapNotNull(byId::get)
        val primary = matched.firstOrNull { it.attentionNote.isNotBlank() } ?: matched.firstOrNull()
        val guess = DeviceExplain.guess(sighting.copy(fleetIds = ids), matched.map { it.name })
        return Classification(
            family = primary?.kind?.let(::familyOf),
            signatureName = primary?.name,
            signatureIds = ids,
            guess = CalmCopy.orFallback(guess.headline, fallback = ""),
            guessConfidence = when (guess.confidence) {
                DeviceExplain.Confidence.HIGH -> Confidence.HIGH
                DeviceExplain.Confidence.MEDIUM -> Confidence.MEDIUM
                DeviceExplain.Confidence.LOW -> Confidence.LOW
            },
            notable = matched.any { it.attentionNote.isNotBlank() },
        )
    }

    companion object {
        fun familyOf(kind: SignatureClass): DeviceFamily = when (kind.folded()) {
            SignatureClass.FINDER -> DeviceFamily.FINDER_TAG
            SignatureClass.BEACON -> DeviceFamily.BEACON
            SignatureClass.SIGNAGE -> DeviceFamily.SIGNAGE
            SignatureClass.WEARABLE, SignatureClass.BODYWORN -> DeviceFamily.WEARABLE
            SignatureClass.SURVEILLANCE, SignatureClass.CAMERA -> DeviceFamily.CAMERA
            SignatureClass.DRONE -> DeviceFamily.DRONE
            SignatureClass.HACKING -> DeviceFamily.TEST_TOOL
            SignatureClass.LAW_ENFORCEMENT -> DeviceFamily.PUBLIC_SAFETY
            SignatureClass.VEHICLE -> DeviceFamily.VEHICLE
            SignatureClass.GLASSES -> DeviceFamily.GLASSES
            SignatureClass.AUDIO -> DeviceFamily.AUDIO
            SignatureClass.THERMOSTAT -> DeviceFamily.THERMOSTAT
            SignatureClass.LOCK -> DeviceFamily.ACCESS_CONTROL
            SignatureClass.HEALTH -> DeviceFamily.HEALTH
            SignatureClass.HOME -> DeviceFamily.SMART_HOME
            SignatureClass.ISP -> DeviceFamily.ROUTER
            SignatureClass.MESH -> DeviceFamily.MESH_RADIO
            SignatureClass.PHONE -> DeviceFamily.PHONE_PC
            SignatureClass.OTHER -> DeviceFamily.OTHER
        }
    }
}
