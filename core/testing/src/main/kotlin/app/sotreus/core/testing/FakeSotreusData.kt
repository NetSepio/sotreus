package app.sotreus.core.testing

import app.sotreus.core.data.live.LiveRadio
import app.sotreus.core.data.live.LiveSnapshot
import app.sotreus.core.data.repository.AttentionDetail
import app.sotreus.core.data.repository.EntityDetail
import app.sotreus.core.data.repository.EvidenceRow
import app.sotreus.core.data.repository.SessionListItem
import app.sotreus.core.database.dao.PlaceEncounterSummary
import app.sotreus.core.database.entity.AttentionEventEntity
import app.sotreus.core.database.entity.EntityEntity
import app.sotreus.core.database.entity.FriendEntity
import app.sotreus.core.database.entity.ObservationEntity
import app.sotreus.core.database.entity.PlaceEntity
import app.sotreus.core.database.entity.ProofBatchEntity
import app.sotreus.core.database.entity.SessionEntity
import app.sotreus.core.model.AttentionHeadline
import app.sotreus.core.model.AttentionInputs
import app.sotreus.core.model.AttentionReason
import app.sotreus.core.model.AttentionReasonKind
import app.sotreus.core.model.BleAddressType
import app.sotreus.core.model.Confidence
import app.sotreus.core.model.DeviceFamily
import app.sotreus.core.model.PresenceState
import app.sotreus.core.model.ProofState
import app.sotreus.core.model.RadioKind
import app.sotreus.core.model.SessionKind
import app.sotreus.core.model.SolanaCluster
import app.sotreus.core.model.UserEntityState
import app.sotreus.sensing.RadioAccess
import app.sotreus.sensing.RadioStatus

/**
 * The sample world the mocks show: place Office (14 visits), 42 radios / 7 families / 31 familiar /
 * 3 new / 1 seen elsewhere; Grey tag (finder tag, tagged, 9 encounters at Home/Office/Café); an
 * attention event at 0.74; NS-Office 38 s old and throttled; stale HP-Print-3C; sessions; friends
 * Ana, Kiran, Mei; a 304-record mainnet proof batch. Times are relative to [NOW].
 */
object FakeSotreusData {
    const val NOW = 1_791_324_060_000L // 6 Oct 2026, 21:41 local-ish
    private const val MIN = 60_000L
    private const val DAY = 24 * 60 * MIN

    val office = PlaceEntity(id = 1, name = "Office", createdAtMs = NOW - 40 * DAY, updatedAtMs = NOW - 12 * 60 * MIN)
    val home = PlaceEntity(id = 2, name = "Home", createdAtMs = NOW - 60 * DAY, updatedAtMs = NOW - DAY)
    val cafe = PlaceEntity(id = 3, name = "Café", createdAtMs = NOW - 20 * DAY, updatedAtMs = NOW - 4 * DAY)
    val places = listOf(office, home, cafe)

    val greyTag = entity("ble:C3:91:44:0B:62:1A", RadioKind.BLE, "Tile", DeviceFamily.FINDER_TAG, "Tile Trackers", "Most likely a finder tag", -58, UserEntityState.TAGGED, userName = "Grey tag", first = NOW - 8 * DAY)
    val camera = entity("ble:5A:77:21:0C:33:01", RadioKind.BLE, null, DeviceFamily.CAMERA, "eufy Security", "Probably a camera", -67, notable = true, first = NOW - 2 * MIN)
    val nsOffice = entity("wifi:00:0F:B3:11:22:33", RadioKind.WIFI, "NS-Office", DeviceFamily.ROUTER, null, "Wi-Fi access point", -49, UserEntityState.EXPECTED, security = "[WPA2-PSK-CCMP][RSN-SAE-CCMP][ESS]", first = NOW - 40 * DAY)
    val audio = entity("ble:7E:22:90:12:3C:44", RadioKind.BLE, null, DeviceFamily.AUDIO, "Fast Pair", "Probably earbuds or a speaker", -72, first = NOW - 20 * DAY)
    val advertiser = entity("ble:4D:13:65:AA:01:22", RadioKind.BLE, null, null, null, "Bluetooth LE advertiser", -81, first = NOW - MIN)
    val printer = entity("wifi:3C:D9:2B:41:7F:00", RadioKind.WIFI, "HP-Print-3C", DeviceFamily.OTHER, "HP", "Wi-Fi access point", -76, security = "[WPA2-PSK-CCMP][ESS]", first = NOW - 30 * DAY)
    val earbuds = entity("ble:62:09:3F:CC:20:10", RadioKind.BLE, null, DeviceFamily.AUDIO, "Fast Pair", "Probably earbuds", -55, UserEntityState.MINE, userName = "My earbuds", first = NOW - 90 * DAY)

    val entities = listOf(greyTag, camera, nsOffice, audio, advertiser, printer, earbuds)

    private fun live(e: EntityEntity, presence: PresenceState, ageMs: Long, stale: Boolean = false, attention: Boolean = false, otherPlaces: Int = 0) = LiveRadio(
        entityId = e.id, kind = e.radio, advertisedName = e.advertisedName, userName = e.userName, family = e.family, signatureName = e.signatureName,
        guess = e.guess, userState = e.userState, presence = presence, otherPlaces = otherPlaces, avgRssi30 = e.lastRssi.toDouble(), lastRssi = e.lastRssi,
        lastHeardMs = NOW - ageMs, stale = stale, security = e.security, frequencyMhz = if (e.radio == RadioKind.WIFI) 2437 else null, randomAddress = e.radio == RadioKind.BLE,
        needsAttention = attention, simulated = false,
    )

    val liveRadios: List<LiveRadio> = buildList {
        add(live(greyTag, PresenceState.SEEN_ELSEWHERE, 1_000, attention = true, otherPlaces = 2))
        add(live(camera, PresenceState.NEW, 2_000, attention = true))
        add(live(nsOffice, PresenceState.FAMILIAR, 38_000))
        add(live(audio, PresenceState.FAMILIAR, 4_000))
        add(live(advertiser, PresenceState.NEW, 6_000))
        add(live(printer, PresenceState.FAMILIAR, 41_000, stale = true))
        add(live(earbuds, PresenceState.NEW, 3_000))
        repeat(28) { i ->
            val e = entity("ble:F%01X:00:00:00:00:%02X".format(i % 16, i), if (i % 4 == 0) RadioKind.WIFI else RadioKind.BLE, null, listOf(DeviceFamily.PHONE_PC, DeviceFamily.WEARABLE, DeviceFamily.ROUTER, DeviceFamily.SMART_HOME, DeviceFamily.AUDIO)[i % 5], null, null, -62 - (i * 3) % 30)
            add(live(e, PresenceState.FAMILIAR, (i % 9) * 1_000L))
        }
    }

    val status = RadioStatus(
        running = true, bleRunning = true, bleLastResultMs = NOW - 2_000, bleResultsPerMinute = 1840,
        wifiLastFreshMs = NOW - 38_000, wifiScanRequested = true, wifiNextScanMs = NOW + 22_000, wifiLastRequestRejected = true,
    )

    val access = RadioAccess(granted = emptySet(), bluetoothOn = true, wifiOn = true, locationOn = true, bleSupported = true)

    val snapshot = LiveSnapshot(
        atMs = NOW, observing = true, radios = liveRadios, status = status, access = access,
        placeId = office.id, placeName = office.name, baselineVisits = 14,
    )

    val attentionEvent = AttentionEventEntity(
        id = 7, entityId = greyTag.id, createdAtMs = NOW, updatedAtMs = NOW, placeId = office.id, sessionId = null, visitId = 21,
        score = 0.74f, headline = AttentionHeadline.CROSS_LOCATION_REENCOUNTER, reasonsJson = "[]", inputsJson = "{}",
    )

    val attentionReasons = listOf(
        AttentionReason(AttentionReasonKind.TAGGED_BY_YOU, listOf("Grey tag")),
        AttentionReason(AttentionReasonKind.SEEN_AT_OTHER_PLACES, listOf("Home", "Café")),
        AttentionReason(AttentionReasonKind.REPEATED_THIS_SESSION, listOf("38", "41")),
        AttentionReason(AttentionReasonKind.KNOWN_FAMILY, listOf("FINDER_TAG", "HIGH", "Tile Trackers"), strong = false),
    )

    val attentionInputs = AttentionInputs(reEncounter = 0.86f, yourTag = 1f, persistence = 0.72f, novelty = 0.12f, knownSignature = 0.30f, freshness = 0.96f)

    private fun obs(id: Long, ago: Long, rssi: Int, place: Long) =
        ObservationEntity(id = id, entityId = greyTag.id, observedAtMs = NOW - ago, rssi = rssi, radio = RadioKind.BLE, placeId = place, sessionId = null, rawHex = null)

    val evidence = listOf(
        EvidenceRow(obs(41, 0, -58, office.id), "Office"),
        EvidenceRow(obs(40, 4 * DAY - 219 * MIN, -61, cafe.id), "Café"),
        EvidenceRow(obs(39, 8 * DAY - 806 * MIN, -55, home.id), "Home"),
    )

    val attentionDetail = AttentionDetail(attentionEvent, greyTag, attentionReasons, attentionInputs, evidence, evidenceTotal = 9, placeName = "Office")

    val greyTagDetail = EntityDetail(
        entity = greyTag.copy(observationCount = 41),
        encounterCount = 9,
        byPlace = listOf(
            PlaceEncounterSummary(home.id, "Home", 4, NOW - 8 * DAY),
            PlaceEncounterSummary(office.id, "Office", 3, NOW),
            PlaceEncounterSummary(cafe.id, "Café", 2, NOW - 4 * DAY),
        ),
        observationCount = 41,
        proofBatches = emptyList(),
        attention = listOf(attentionEvent),
    )

    val sessions = listOf(
        SessionListItem(SessionEntity(1, SessionKind.SIT, "Office · morning", NOW - 12 * 60 * MIN, NOW - 12 * 60 * MIN + 42 * MIN, false, office.id, "Office"), 118, 9, 2),
        SessionListItem(SessionEntity(2, SessionKind.JOURNEY, "Home → Café", NOW - 4 * DAY, NOW - 4 * DAY + 68 * MIN, true, null, "Home"), 304, 41, 1),
        SessionListItem(SessionEntity(3, SessionKind.SIT, "Hotel room", NOW - 2 * DAY, NOW - 2 * DAY + 422 * MIN, false, null, null), 60, 14, 0),
        SessionListItem(SessionEntity(4, SessionKind.SIT, "Hotel room", NOW - 3 * DAY, NOW - 3 * DAY + 375 * MIN, false, null, null), 51, 5, 0),
    )

    val friends = listOf(
        FriendEntity(1, "Ana", "aa".repeat(32), "", NOW - 30 * DAY, NOW - 20 * MIN, "Office"),
        FriendEntity(2, "Kiran", "bb".repeat(32), "", NOW - 34 * DAY),
        FriendEntity(3, "Mei", "cc".repeat(32), "", NOW - 53 * DAY, NOW - DAY, null),
    )

    val proofBatch = ProofBatchEntity(
        id = 1, sessionId = 2, title = "Home → Café", createdAtMs = NOW - 4 * DAY, recordCount = 304, observationCount = 290, attentionCount = 14,
        merkleRootHex = "71bd" + "0".repeat(56) + "0c3a", commitmentHex = "a3f91c075be2" + "0".repeat(44) + "88d19e42", nonceHex = "ab".repeat(32),
        schema = "SOTREUS_BATCH_V1", cluster = SolanaCluster.MAINNET_BETA, txSignature = "5Kt9" + "x".repeat(80) + "q2Rf", state = ProofState.FINALIZED,
        blockTimeMs = NOW - 4 * DAY + 70 * MIN, walletAddress = "7xKpQh2mD9a5Vw8Lr3TnB6cY1sFzE4uJg0Ho3fQe",
    )

    const val WALLET = "7xKpQh2mD9a5Vw8Lr3TnB6cY1sFzE4uJg0Ho3fQe"

    private fun entity(
        id: String, kind: RadioKind, name: String?, family: DeviceFamily?, signature: String?, guess: String?, rssi: Int,
        state: UserEntityState = UserEntityState.UNCLASSIFIED, userName: String? = null, notable: Boolean = false, security: String? = null,
        first: Long = NOW - DAY,
    ) = EntityEntity(
        id = id, radio = kind, address = id.substringAfter(':'), addressType = if (kind == RadioKind.BLE) BleAddressType.RANDOM else BleAddressType.PUBLIC,
        linkConfidence = if (kind == RadioKind.BLE) Confidence.MEDIUM else Confidence.HIGH, advertisedName = name, userName = userName, userState = state,
        note = null, family = family, signatureName = signature, guess = guess, guessConfidence = Confidence.HIGH, notable = notable, vendor = null,
        companyId = if (kind == RadioKind.BLE) 0x0075 else null, serviceUuids = if (family == DeviceFamily.FINDER_TAG) "FD5A" else "",
        serviceData = if (family == DeviceFamily.FINDER_TAG) "FD5A:22" else null, connectable = true, txPower = null, security = security,
        frequencyMhz = if (kind == RadioKind.WIFI) 2437 else null, channel = if (kind == RadioKind.WIFI) 6 else null, wifiStandard = null,
        firstSeenMs = first, lastSeenMs = NOW, lastRssi = rssi, observationCount = 41,
    )
}
