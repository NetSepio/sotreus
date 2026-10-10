package app.sotreus.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.sotreus.core.model.AircraftMode
import app.sotreus.core.model.GeotagMode
import app.sotreus.core.model.SatelliteGroup
import app.sotreus.core.model.NowView
import app.sotreus.core.model.RetentionPolicy
import app.sotreus.core.model.ScanIntensity
import app.sotreus.core.model.SotreusSettings
import app.sotreus.core.model.StampingMode
import app.sotreus.intelligence.PlaceGeofence
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore("sotreus_settings")

/** On-device preferences. Never synced. */
@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private object Keys {
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val currentPlace = longPreferencesKey("current_place_id")
        val currentPlaceAuto = booleanPreferencesKey("current_place_auto")
        val placeSelectedAt = longPreferencesKey("current_place_selected_at_ms")
        val placeAnchorLat = doublePreferencesKey("current_place_anchor_lat")
        val placeAnchorLon = doublePreferencesKey("current_place_anchor_lon")
        val placeByLocation = booleanPreferencesKey("place_by_location")
        val plusCodeTags = booleanPreferencesKey("plus_code_tags")
        val nowView = stringPreferencesKey("now_view")
        val retention = stringPreferencesKey("retention")
        val mask = booleanPreferencesKey("mask_coordinates")
        val geotagMode = stringPreferencesKey("geotag_mode")
        val geotagNext = booleanPreferencesKey("geotag_next")
        val intensity = stringPreferencesKey("scan_intensity")
        val staleHold = intPreferencesKey("stale_hold_s")
        val stamping = stringPreferencesKey("stamping_mode")
        val presence = booleanPreferencesKey("nearby_presence")
        val tick = booleanPreferencesKey("tick_sound")
        val simulated = booleanPreferencesKey("simulated_radios")
        val forceSolana = booleanPreferencesKey("force_solana_ui")
        val showAddresses = booleanPreferencesKey("show_addresses")
        val contextEnabled = booleanPreferencesKey("context_enabled")
        val aircraftMode = stringPreferencesKey("aircraft_mode")
        val aircraftRadius = intPreferencesKey("aircraft_radius_km")
        val satellitesEnabled = booleanPreferencesKey("satellites_enabled")
        val satelliteGroups = stringPreferencesKey("satellite_groups")
        val remoteIdEnabled = booleanPreferencesKey("remote_id_enabled")
        val presenceKey = stringPreferencesKey("presence_key_wrapped")
        val walletAuthToken = stringPreferencesKey("wallet_auth_token_mainnet_wrapped")
        val walletPackage = stringPreferencesKey("wallet_package")
        val legacyWalletAuthToken = stringPreferencesKey("wallet_auth_token_wrapped")
        val notificationsAsked = booleanPreferencesKey("notifications_asked")
    }

    private val store get() = context.settingsStore

    val settings: Flow<SotreusSettings> = store.data.map { p ->
        val d = SotreusSettings()
        SotreusSettings(
            onboardingDone = p[Keys.onboardingDone] ?: d.onboardingDone,
            currentPlaceId = p[Keys.currentPlace]?.takeIf { it > 0 },
            currentPlaceByLocation = p[Keys.currentPlaceAuto] ?: d.currentPlaceByLocation,
            placeByLocation = p[Keys.placeByLocation] ?: d.placeByLocation,
            plusCodeTags = p[Keys.plusCodeTags] ?: d.plusCodeTags,
            nowView = p[Keys.nowView].enumOr(d.nowView),
            retention = p[Keys.retention].enumOr(d.retention),
            maskCoordinates = p[Keys.mask] ?: d.maskCoordinates,
            geotagMode = p[Keys.geotagMode].enumOr(d.geotagMode),
            geotagNextSession = p[Keys.geotagNext] ?: d.geotagNextSession,
            scanIntensity = p[Keys.intensity].enumOr(d.scanIntensity),
            staleHoldSeconds = p[Keys.staleHold] ?: d.staleHoldSeconds,
            stampingMode = p[Keys.stamping].enumOr(d.stampingMode),
            nearbyPresence = p[Keys.presence] ?: d.nearbyPresence,
            tickSound = p[Keys.tick] ?: d.tickSound,
            simulatedRadios = p[Keys.simulated] ?: d.simulatedRadios,
            forceSolanaUi = p[Keys.forceSolana] ?: d.forceSolanaUi,
            showAddresses = p[Keys.showAddresses] ?: d.showAddresses,
            contextEnabled = p[Keys.contextEnabled] ?: d.contextEnabled,
            aircraftMode = p[Keys.aircraftMode].enumOr(d.aircraftMode),
            aircraftRadiusKm = p[Keys.aircraftRadius] ?: d.aircraftRadiusKm,
            satellitesEnabled = p[Keys.satellitesEnabled] ?: d.satellitesEnabled,
            satelliteGroups = p[Keys.satelliteGroups]?.split(",")?.mapNotNull { n -> SatelliteGroup.entries.firstOrNull { it.name == n } }?.toSet()
                ?: d.satelliteGroups,
            remoteIdEnabled = p[Keys.remoteIdEnabled] ?: d.remoteIdEnabled,
        )
    }

    suspend fun current(): SotreusSettings = settings.first()

    suspend fun setOnboardingDone() { store.edit { it[Keys.onboardingDone] = true } }
    // --- Current place -------------------------------------------------------------------------
    // A place is picked by hand (remembering where the phone was, so the pick holds only nearby) or by
    // phone location. Location writes are conditional on the selection they decided from, so they never
    // overwrite a hand pick made meanwhile. The anchor is one point on this phone, never a history.

    suspend fun placeSelection(): PlaceGeofence.Selection {
        val p = store.data.first()
        return PlaceGeofence.Selection(
            placeId = p[Keys.currentPlace]?.takeIf { it > 0 },
            auto = p[Keys.currentPlaceAuto] ?: false,
            anchorLat = p[Keys.placeAnchorLat],
            anchorLon = p[Keys.placeAnchorLon],
            selectedAtMs = p[Keys.placeSelectedAt] ?: 0L,
        )
    }

    /** The user picked [id] (null = Unsaved place) by hand, standing at [anchorLat]/[anchorLon] if known. */
    suspend fun selectPlaceByHand(id: Long?, anchorLat: Double?, anchorLon: Double?, now: Long) {
        store.edit {
            it[Keys.currentPlace] = id ?: 0L
            it[Keys.currentPlaceAuto] = false
            it[Keys.placeSelectedAt] = now
            if (anchorLat != null && anchorLon != null) {
                it[Keys.placeAnchorLat] = anchorLat
                it[Keys.placeAnchorLon] = anchorLon
            } else {
                it.remove(Keys.placeAnchorLat)
                it.remove(Keys.placeAnchorLon)
            }
        }
    }

    /** Phone location picked [id] (null = no saved place). Skipped if the selection changed since [decidedFrom]. */
    suspend fun selectPlaceByLocation(id: Long?, now: Long, decidedFrom: PlaceGeofence.Selection) {
        store.edit {
            if ((it[Keys.placeSelectedAt] ?: 0L) != decidedFrom.selectedAtMs) return@edit
            it[Keys.currentPlace] = id ?: 0L
            it[Keys.currentPlaceAuto] = true
            it[Keys.placeSelectedAt] = now
            it.remove(Keys.placeAnchorLat)
            it.remove(Keys.placeAnchorLon)
        }
    }

    /** Records where a hand pick made without a fix was made. Skipped if the selection changed since [decidedFrom]. */
    suspend fun anchorPlaceSelection(lat: Double, lon: Double, decidedFrom: PlaceGeofence.Selection) {
        store.edit {
            if ((it[Keys.placeSelectedAt] ?: 0L) != decidedFrom.selectedAtMs || it[Keys.currentPlaceAuto] == true) return@edit
            it[Keys.placeAnchorLat] = lat
            it[Keys.placeAnchorLon] = lon
        }
    }

    /** Forgets where a hand pick was made (with the rest of the phone's location data). */
    suspend fun clearPlaceAnchor() {
        store.edit {
            it.remove(Keys.placeAnchorLat)
            it.remove(Keys.placeAnchorLon)
        }
    }

    suspend fun setPlaceByLocation(v: Boolean) { store.edit { it[Keys.placeByLocation] = v } }
    suspend fun setPlusCodeTags(v: Boolean) { store.edit { it[Keys.plusCodeTags] = v } }
    suspend fun setNowView(v: NowView) { store.edit { it[Keys.nowView] = v.name } }
    suspend fun setRetention(v: RetentionPolicy) { store.edit { it[Keys.retention] = v.name } }
    suspend fun setMaskCoordinates(v: Boolean) { store.edit { it[Keys.mask] = v } }
    suspend fun setGeotagMode(v: GeotagMode) { store.edit { it[Keys.geotagMode] = v.name } }
    suspend fun setGeotagNextSession(v: Boolean) { store.edit { it[Keys.geotagNext] = v } }
    suspend fun setScanIntensity(v: ScanIntensity) { store.edit { it[Keys.intensity] = v.name } }
    suspend fun setStampingMode(v: StampingMode) { store.edit { it[Keys.stamping] = v.name } }
    suspend fun setNearbyPresence(v: Boolean) { store.edit { it[Keys.presence] = v } }
    suspend fun setTickSound(v: Boolean) { store.edit { it[Keys.tick] = v } }
    suspend fun setSimulatedRadios(v: Boolean) { store.edit { it[Keys.simulated] = v } }
    suspend fun setContextEnabled(v: Boolean) { store.edit { it[Keys.contextEnabled] = v } }
    suspend fun setAircraftMode(v: AircraftMode) { store.edit { it[Keys.aircraftMode] = v.name } }
    suspend fun setAircraftRadiusKm(v: Int) { store.edit { it[Keys.aircraftRadius] = v } }
    suspend fun setSatellitesEnabled(v: Boolean) { store.edit { it[Keys.satellitesEnabled] = v } }
    suspend fun setSatelliteGroups(v: Set<SatelliteGroup>) { store.edit { it[Keys.satelliteGroups] = v.joinToString(",") { g -> g.name } } }
    suspend fun setRemoteIdEnabled(v: Boolean) { store.edit { it[Keys.remoteIdEnabled] = v } }
    suspend fun setShowAddresses(v: Boolean) { store.edit { it[Keys.showAddresses] = v } }
    suspend fun setForceSolanaUi(v: Boolean) { store.edit { it[Keys.forceSolana] = v } }

    // Wrapped (Keystore-encrypted) secrets. Callers wrap/unwrap with SecretBox.
    suspend fun presenceKeyWrapped(): String? = store.data.first()[Keys.presenceKey]
    suspend fun setPresenceKeyWrapped(v: String?) { store.edit { if (v == null) it.remove(Keys.presenceKey) else it[Keys.presenceKey] = v } }
    suspend fun walletAuthTokenWrapped(): String? = store.data.first()[Keys.walletAuthToken]
    suspend fun setWalletAuthTokenWrapped(v: String?) {
        store.edit {
            it.remove(Keys.legacyWalletAuthToken)
            if (v == null) it.remove(Keys.walletAuthToken) else it[Keys.walletAuthToken] = v
        }
    }

    /** The wallet app chosen at sign-in; later wallet requests go only to it. */
    suspend fun walletPackage(): String? = store.data.first()[Keys.walletPackage]
    suspend fun setWalletPackage(v: String?) { store.edit { if (v == null) it.remove(Keys.walletPackage) else it[Keys.walletPackage] = v } }

    /** The optional session-notice permission is asked for once, in context, never again. */
    suspend fun notificationsAsked(): Boolean = store.data.first()[Keys.notificationsAsked] ?: false
    suspend fun setNotificationsAsked() { store.edit { it[Keys.notificationsAsked] = true } }

    private inline fun <reified E : Enum<E>> String?.enumOr(default: E): E =
        this?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default
}
