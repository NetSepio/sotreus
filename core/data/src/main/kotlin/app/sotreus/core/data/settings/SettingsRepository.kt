package app.sotreus.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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
    suspend fun setCurrentPlace(id: Long?) { store.edit { it[Keys.currentPlace] = id ?: 0L } }
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
