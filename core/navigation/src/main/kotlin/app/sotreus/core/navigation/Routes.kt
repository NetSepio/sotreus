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
@Serializable data object SettingsRoute

// Awareness detail
@Serializable data class EntityRoute(val entityId: String)
@Serializable data class ProximityRoute(val entityId: String)
@Serializable data class EvidenceRoute(val entityId: String)
@Serializable data class AttentionRoute(val eventId: Long)
@Serializable data class PlaceRoute(val placeId: Long)
@Serializable data object PlacesRoute

// Sessions
@Serializable data class SessionLiveRoute(val sessionId: Long)
@Serializable data class SessionRoute(val sessionId: Long)
@Serializable data class CompareRoute(val a: Long = -1, val b: Long = -1)

// Settings
@Serializable data object SensorsRoute
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
