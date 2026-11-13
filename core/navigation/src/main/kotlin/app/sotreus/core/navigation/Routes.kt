package app.sotreus.core.navigation

import kotlinx.serialization.Serializable

// Type-safe routes for every V1 destination (HANDOFF_V1_UI.md §5). Feature modules navigate
// by route value only, so they never depend on each other.

// First run
@Serializable data object WelcomeRoute
@Serializable data object PermissionsRoute
@Serializable data object AboutRoute

// Tab roots
@Serializable data object NowRoute
@Serializable data object HistoryRoute
@Serializable data object JourneyRoute
@Serializable data object ContextRoute
@Serializable data object SettingsRoute

// Awareness detail
@Serializable data class EntityRoute(val entityId: String)
@Serializable data class ProximityRoute(val entityId: String)
@Serializable data class EvidenceRoute(val entityId: String)
@Serializable data class AttentionRoute(val eventId: Long)
@Serializable data class PlaceRoute(val placeId: Long)
@Serializable data object PlacesRoute
@Serializable data class PlaceMapRoute(val placeId: Long)

// Sessions
@Serializable data class SessionLiveRoute(val sessionId: Long)
@Serializable data class SessionRoute(val sessionId: Long)
@Serializable data class CompareRoute(val a: Long = -1, val b: Long = -1)

// Context sources
@Serializable data class AircraftRoute(val icao24: String)
@Serializable data class SatelliteRoute(val noradId: Int)
@Serializable data class RemoteIdRoute(val subjectId: String)
@Serializable data object ContextSourcesRoute

// Settings
@Serializable data object FieldToolsRoute
@Serializable data object LanguagesRoute
@Serializable data class RouteMapRoute(val sessionId: Long = -1)
@Serializable data object SensorsRoute

/** V2: Sotreus Edge: pairing when none is paired, otherwise the device screen. */
@Serializable data object EdgeRoute
@Serializable data object EdgeMapRoute

// V3 Mesh mode (HANDOFF_V3_MESH.md): Mesh takes Now's tab while the Edge is in Mesh mode.
@Serializable data object MeshRoute
/** [conversation] is `ch:<index>` or `dm:<node number>`. */
@Serializable data class MeshConversationRoute(val conversation: String)
@Serializable data class MeshNodeRoute(val num: Long)
@Serializable data object MeshSettingsRoute
@Serializable data object MeshChannelsRoute
/** Picks the fixed mesh position on a map (Mesh settings, M8). */
@Serializable data object MeshPositionMapRoute
@Serializable data object DiagnosticsRoute
@Serializable data object SignaturesRoute
@Serializable data object PrivacyRoute
@Serializable data object FriendsRoute
@Serializable data object QrShowRoute
@Serializable data object QrScanRoute
@Serializable data object ProfileRoute

// Solana Mobile devices only
@Serializable data object WalletRoute
@Serializable data object ProofsRoute
@Serializable data class StampRoute(val batchId: Long)
@Serializable data class ReceiptRoute(val batchId: Long)
