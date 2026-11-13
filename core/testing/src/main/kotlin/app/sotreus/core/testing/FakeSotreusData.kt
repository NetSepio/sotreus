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

    /** An automatic place (docs/plans/auto-places.md): named by its plus code until the owner renames it. */
    val autoPlace = PlaceEntity(id = 4, name = "7J4VWP3R+2X", createdAtMs = NOW - 2 * DAY, updatedAtMs = NOW - 3 * MIN, lat = 12.93276, lon = 77.74468, radiusM = 50, auto = true)

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

    /** A short walk for route-map previews (lat, lon). Made up; not anyone's real path. */
    val ROUTE: List<Pair<Double, Double>> = List(24) { i -> 47.6062 + i * 0.00018 to -122.3321 + kotlin.math.sin(i / 4.0) * 0.0006 }

    // Environment context (architecture handoff §14.5). Places and names are placeholders.
    val AIR = app.sotreus.core.model.AirReading(34, 52, 11.0, 19.0, 62.0, 14.0, NOW - 2 * 3_600_000L)
    val QUAKES = listOf(
        app.sotreus.core.model.Nearby(app.sotreus.core.model.Quake("us7000fake1", 4.8, "52 km SSE of Example Town", NOW - 3 * 3_600_000L, NOW, 0.9, 104.2, 10.0, "https://earthquake.usgs.gov/"), 140.0),
        app.sotreus.core.model.Nearby(app.sotreus.core.model.Quake("us7000fake2", 2.9, "Example Strait", NOW - 2 * 86_400_000L, NOW, 2.4, 102.1, 35.0, null), 260.0),
    )
    val HAZARDS = listOf(
        app.sotreus.core.model.Nearby(
            app.sotreus.core.model.Hazard(
                "TC-1000001", app.sotreus.core.model.HazardType.TROPICAL_CYCLONE, app.sotreus.core.model.HazardLevel.ORANGE, "Tropical Cyclone EXAMPLE-26",
                "Tropical storm (maximum wind speed of 102 km/h)", NOW - 2 * 86_400_000L, NOW - 3_600_000L, 6.0, 108.0, "https://www.gdacs.org/",
            ),
            610.0,
        ),
    )

    val NWS_ALERTS = listOf(
        app.sotreus.core.model.WeatherAlert("urn:fake:1", app.sotreus.core.model.AlertSource.NWS, "Winter Weather Advisory", null, "Moderate", "Example Mountains", NOW - 3_600_000L, NOW + 20 * 3_600_000L, "NWS Example", null),
        app.sotreus.core.model.WeatherAlert("urn:fake:2", app.sotreus.core.model.AlertSource.NWS, "Frost Advisory", null, "Minor", "Example Valley", NOW + 6 * 3_600_000L, NOW + 14 * 3_600_000L, "NWS Example", null),
    )
    val METEOALARM_GROUPS = listOf(
        app.sotreus.core.model.AlertGroup("Snow-ice Warning", "Yellow", "Minor", listOf("Kreis A", "Kreis B", "Kreis C", "Kreis D"), NOW + 2 * 3_600_000L, NOW + 14 * 3_600_000L, "https://meteoalarm.org"),
    )
    val STATION = app.sotreus.core.model.StationReading(
        2178, "Example Station", "AirNow", 7.4,
        listOf(
            app.sotreus.core.model.StationValue("PM2.5", "µg/m³", 21.4, NOW - 40 * 60_000L),
            app.sotreus.core.model.StationValue("O₃", "ppm", 0.031, NOW - 100 * 60_000L),
        ),
        "Example Board",
    )

    // ---- V2: Sotreus Edge (previews only) ----------------------------------------------------

    private val edgeCaps = app.sotreus.edge.protocol.EdgeCapabilities(app.sotreus.edge.fake.FakeEdge.DEFAULT_CAPS)

    val edgeInfo = app.sotreus.edge.protocol.EdgeInfo(
        protocol = 1, deviceId = "9f3c00aa11bb22cc", name = "Sotreus Edge", suffix = "96FF", firmware = "0.2.0",
        hardware = "heltec_wifi_lora_32_v3", boot = 12, mdnsHost = "sotreus-96ff.local", recordSize = 256, recordFormat = 1,
        probeSalt = "0a0b0c0d", caps = edgeCaps,
    )

    val edgeDevice = app.sotreus.core.database.entity.EdgeDeviceEntity(
        deviceId = edgeInfo.deviceId, name = edgeInfo.name, suffix = edgeInfo.suffix, hardware = edgeInfo.hardware, firmware = edgeInfo.firmware,
        protocol = 1, capsJson = "{}", probeSalt = edgeInfo.probeSalt, btAddress = "C4:5B:BE:96:FF:01", pairedAtMs = NOW - 4 * DAY,
        lastConnectedMs = NOW - 2_000, lastSyncMs = NOW - 12_000, syncCursor = 4_813,
    )

    val edgeStatus = app.sotreus.edge.protocol.EdgeStatus(
        uptimeMs = 3_600_000, timeValid = true, timeSource = "phone", unixMs = NOW, mode = app.sotreus.edge.protocol.EdgeMode.AWARENESS,
        battery = app.sotreus.edge.protocol.EdgeBattery(true, "battery", 3_950, 82),
        link = app.sotreus.edge.protocol.EdgeWifiLinkStatus(app.sotreus.edge.protocol.EdgeLinkMode.OFF, "off", "", "", 0, 6, sniffing = true, hopping = true),
        awareness = app.sotreus.edge.protocol.EdgeAwarenessStatus(true, 31, 9, 6, 2_400, 400, 4_812, 0),
        log = app.sotreus.edge.protocol.EdgeLogStatus(head = 4_812, oldest = 1, count = 4_812, capacity = 14_000, erasing = false, writeFailures = 0),
        eventsDropped = 0,
    )

    val edgeLive = app.sotreus.core.data.edge.EdgeLive(
        device = edgeDevice, link = app.sotreus.edge.link.EdgeLinkState.Connected(517), info = edgeInfo, status = edgeStatus, statusAtMs = NOW,
        config = app.sotreus.edge.protocol.EdgeConfig(
            app.sotreus.edge.protocol.EdgeMode.AWARENESS, app.sotreus.edge.protocol.EdgeLinkMode.OFF,
            app.sotreus.edge.protocol.EdgeAwarenessConfig(true, true, true, true, false, -100, 60, 300),
            app.sotreus.edge.protocol.EdgePlacement(app.sotreus.edge.protocol.DeploymentMode.TRAVEL), kotlinx.serialization.json.JsonObject(emptyMap()),
        ),
        lastLiveMs = NOW - 2_000, openSightings = 46, clockOffsetMs = 200, linkRssi = -58, together = true,
        locationSource = app.sotreus.edge.location.EdgeLocationSource.PHONE_WITH_EDGE,
    )

    val edgeFix = app.sotreus.edge.location.GeoFix(
        NOW - 1_000, 12.9716, 77.5946, 6f, altitudeM = 920.0, verticalAccuracyM = 4f, mslAltitudeM = 212.0, satellitesUsed = 9, provider = "gps",
    )

    /** Stationary position: an estimate inside the 30 m limit, one outside it, and a saved placement. */
    val edgeGoodEstimate = app.sotreus.edge.location.PlacementAverager.Estimate(12.97160, 77.59460, 9f, 24, accepted = true, limitM = 30f)
    val edgeWideEstimate = app.sotreus.edge.location.PlacementAverager.Estimate(12.97160, 77.59460, 42f, 18, accepted = false, limitM = 30f)
    val edgeStationaryPlacement = app.sotreus.edge.protocol.EdgePlacement(app.sotreus.edge.protocol.DeploymentMode.STATIONARY, 12.97160, 77.59460, 42f, NOW, "phone")
    val edgeStationaryDevice = edgeDevice.copy(
        deployment = app.sotreus.core.model.EdgeDeployment.STATIONARY,
        placeLat = 12.97160, placeLon = 77.59460, placeAccuracyM = 42f, placeSetMs = NOW, placeSource = "phone",
    )

    val edgePaired = app.sotreus.core.data.edge.EdgeRepository.PairResult.Paired(edgeInfo, oldest = 1, head = 4_812, waiting = 4_812, firstMs = null, lastMs = null)

    // ---- V3: Mesh mode (previews only; mocks M1–M8) ------------------------------------------

    const val MESH_SELF = 0x5AC1E0D2L
    const val MESH_BASE = 0xA1B2C3D4L
    const val MESH_HILL = 0x11112222L
    const val MESH_TRAIL = 0x33334444L
    const val MESH_KITE = 0x55556666L
    const val MESH_BUS = 0x00003480L
    const val MESH_BEACON = 0x0000BC09L
    const val PHONE_LAT = 12.97160
    const val PHONE_LON = 77.59460

    val meshConfig: app.sotreus.edge.protocol.mesh.MeshConfig = app.sotreus.edge.protocol.mesh.MeshConfig.parse(
        app.sotreus.edge.protocol.EdgeJson.parseToJsonElement(
            """{"region":"IN","preset":"LONG_FAST","hop_limit":3,"rebroadcast":"all","tx_power":0,"node_id":"!5ac1e0d2",
               "long_name":"Sotreus Edge 96FF","short_name":"SE2B","role":"CLIENT","licensed":false,
               "channels":[{"name":"","psk":"default","position_precision":13},{"name":"Team","psk":"base64:q83vASNFZ4mrze8BI0VniQ==","position_precision":19}],
               "position":{"mode":"client","interval_s":900},"telemetry_interval_s":1800,
               "canned":["OK","On my way","Need assistance","All fine here"],"mqtt":{"enabled":false},
               "admin":{"enabled":true,"mode":"open"},"store_forward":{"server":false}}""",
        ) as kotlinx.serialization.json.JsonObject,
    )!!

    val meshStatus: app.sotreus.edge.protocol.mesh.MeshStatus = app.sotreus.edge.protocol.mesh.MeshStatus.parse(
        app.sotreus.edge.protocol.EdgeJson.parseToJsonElement(
            """{"radio_present":true,"running":true,"node_id":"!5ac1e0d2","node_num":1522655442,"role":"CLIENT","region":"IN","preset":"LONG_FAST",
               "channel_util_pct":4.1,"air_util_tx_pct":0.8,"polite_hold":false,"nodes":6,"store_forward_routers":["!11112222"],
               "admin":{"enabled":true,"mode":"open","keys":0,"requests":0,"refused":0},"counters":{"rx":1284,"tx":96,"relayed":40}}""",
        ) as kotlinx.serialization.json.JsonObject,
    )

    val meshLive = app.sotreus.core.data.mesh.MeshLive(
        deviceId = edgeDevice.deviceId, meshMode = true, connected = true, selfNum = MESH_SELF, status = meshStatus, statusAtMs = NOW - 4_000,
        config = meshConfig, positionChoice = app.sotreus.edge.protocol.mesh.MeshPositionMode.CLIENT, sharing = true, lastSharedMs = NOW - 40_000,
    )

    private fun meshNode(num: Long, long: String, short: String, role: String, heardAgoMs: Long, snr: Float, hops: Int?, battery: Int? = null,
                         lat: Double? = null, lon: Double? = null, bits: Int? = 13, posAgoMs: Long = heardAgoMs, favorite: Boolean = false,
                         ignored: Boolean = false, key: Boolean = true) =
        app.sotreus.core.database.entity.MeshNodeEntity(
            deviceId = edgeDevice.deviceId, num = num, longName = long, shortName = short, role = role, hasKey = key, favorite = favorite, ignored = ignored,
            lastHeardMs = NOW - heardAgoMs, rssi = -98, snr = snr, hopsAway = hops, battery = battery, voltage = battery?.let { 3.3f + it / 140f },
            channelUtil = 4.1f, airUtil = 0.8f, uptimeS = 273_600, telemetryMs = NOW - 6 * 60_000L,
            lat = lat, lon = lon, altM = lat?.let { 920 }, precisionBits = lat?.let { bits }, positionMs = lat?.let { NOW - posAgoMs },
        )

    val meshNodes = listOf(
        meshNode(MESH_BASE, "Base Camp", "BASE", "CLIENT", 2 * 60_000L, 6.5f, 2, 84, 12.9917, 77.6150, posAgoMs = 4 * 60_000L, favorite = true)
            .copy(temperatureC = 24.6f, humidityPct = 61f, pressureHpa = 1009f, neighbors = "$MESH_HILL:7.0,$MESH_TRAIL:-2.5"),
        meshNode(MESH_HILL, "Hill Repeater", "HILL", "ROUTER", 3 * 60_000L, 7.0f, 0, 101, 13.0256, 77.5946, bits = 16, favorite = true),
        meshNode(MESH_TRAIL, "Trailhead", "TRL1", "CLIENT", 9 * 60_000L, -2.5f, 1, 52, 12.9716, 77.6085, bits = 19),
        meshNode(MESH_KITE, "Kite", "KITE", "TAK", 21 * 60_000L, -8.0f, 3, key = false),
        meshNode(MESH_BUS, "Meshtastic 3480", "3480", "CLIENT", DAY, -12.25f, null, key = false),
        meshNode(MESH_BEACON, "Beacon 9", "BCN9", "TRACKER", 2 * 60 * 60_000L, -9f, 4, ignored = true, key = false),
    )

    private fun meshMsg(from: Long, packet: Long, conversation: String, text: String, agoMs: Long, outgoing: Boolean = false, delivery: String? = null,
                        replyId: Long? = null, reaction: Boolean = false, hops: Int? = 2, snr: Float? = 6.5f, read: Boolean = true, pki: Boolean = false) =
        app.sotreus.core.database.entity.MeshMessageEntity(
            deviceId = edgeDevice.deviceId, fromNode = from, packetId = packet,
            toNode = app.sotreus.core.data.mesh.MeshRows.peerOf(conversation)?.let { if (outgoing) it else MESH_SELF } ?: app.sotreus.edge.protocol.mesh.MeshNodeId.BROADCAST,
            channel = app.sotreus.core.data.mesh.MeshRows.channelOf(conversation) ?: 0, conversation = conversation, text = text, outgoing = outgoing,
            atMs = NOW - agoMs, timeFromEdge = true, rssi = if (outgoing) null else -98, snr = if (outgoing) null else snr, hops = if (outgoing) null else hops,
            replyId = replyId, reaction = reaction, pki = pki, delivery = delivery, read = read,
        )

    val meshChannelMessages = listOf(
        meshMsg(MESH_BASE, 101, "ch:0", "Leaving the trailhead now, radio check?", 112 * 60_000L),
        meshMsg(MESH_SELF, 102, "ch:0", "Loud and clear from the car park", 111 * 60_000L, outgoing = true, delivery = "delivered"),
        meshMsg(MESH_TRAIL, 103, "ch:0", "Same here. Water point is open", 104 * 60_000L, hops = 1, snr = -2.5f),
        meshMsg(MESH_BASE, 104, "ch:0", "👍", 103 * 60_000L, replyId = 103, reaction = true),
        meshMsg(MESH_HILL, 105, "ch:0", "👍", 102 * 60_000L, replyId = 103, reaction = true, hops = 0),
        meshMsg(MESH_BASE, 106, "ch:0", "Reached the ridge, all fine", 6 * 60_000L, snr = 5.5f, read = false),
        meshMsg(MESH_SELF, 107, "ch:0", "Great, we leave in 20 min", 3 * 60_000L, outgoing = true, delivery = "relayed", replyId = 106),
        meshMsg(MESH_SELF, 108, "ch:0", "Bring the spare battery", 2 * 60_000L, outgoing = true, delivery = "failed"),
        meshMsg(MESH_SELF, 109, "ch:0", "On my way", 60_000L, outgoing = true, delivery = "sent"),
    )

    val meshConversations = listOf(
        app.sotreus.core.database.dao.MeshConversationRow("ch:0", 0, "Reached the ridge, all fine", false, MESH_BASE, app.sotreus.edge.protocol.mesh.MeshNodeId.BROADCAST, NOW - 2 * 60_000L, null, false, 2),
        app.sotreus.core.database.dao.MeshConversationRow("ch:1", 1, "On my way", true, MESH_SELF, app.sotreus.edge.protocol.mesh.MeshNodeId.BROADCAST, NOW - 14 * 60_000L, "delivered", false, 0),
        app.sotreus.core.database.dao.MeshConversationRow("dm:$MESH_BASE", 0, "Copy that, see you at five", false, MESH_BASE, MESH_SELF, NOW - 6 * 60_000L, null, true, 1),
        app.sotreus.core.database.dao.MeshConversationRow("dm:$MESH_HILL", 0, "Are you relaying the trail?", true, MESH_SELF, MESH_HILL, NOW - 60 * 60_000L, "no_ack", true, 0),
        app.sotreus.core.database.dao.MeshConversationRow("dm:$MESH_BUS", 0, "Hello from the bus stop", false, MESH_BUS, MESH_SELF, NOW - DAY, null, false, 0),
    )

    val meshWaypoints = listOf(
        app.sotreus.core.database.entity.MeshWaypointEntity(edgeDevice.deviceId, 7, MESH_BASE, "Ridge", null, 13.0005, 77.6040, null, null, NOW - 30 * 60_000L),
        app.sotreus.core.database.entity.MeshWaypointEntity(edgeDevice.deviceId, 8, MESH_SELF, "Car park", null, 12.9790, 77.5880, null, null, NOW - 90 * 60_000L),
    )

    val meshRoute = app.sotreus.edge.protocol.mesh.MeshTraceroute(
        requestId = 9, to = MESH_BASE, route = listOf(MESH_HILL), snrTowards = listOf(7.0, 5.5), routeBack = listOf(MESH_HILL), snrBack = listOf(6.0, 6.75),
    )

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
