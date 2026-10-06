package app.sotreus.feature.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sotreus.core.crypto.Base58
import app.sotreus.core.data.device.DeviceProfileRepository
import app.sotreus.core.data.pipeline.ObservationPipeline
import app.sotreus.core.data.repository.FriendRepository
import app.sotreus.core.data.repository.PlaceRepository
import app.sotreus.core.data.repository.ProfileRepository
import app.sotreus.core.data.repository.ProofRepository
import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.database.entity.LinkedIdentityEntity
import app.sotreus.core.database.entity.LocalProfileEntity
import app.sotreus.core.designsystem.icon.SotreusIcons
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.model.AppBuildInfo
import app.sotreus.core.model.Distribution
import app.sotreus.core.model.ProofState
import app.sotreus.core.model.RetentionPolicy
import app.sotreus.core.model.SotreusSettings
import app.sotreus.core.model.StampingMode
import app.sotreus.core.navigation.AboutRoute
import app.sotreus.core.navigation.ContextSourcesRoute
import app.sotreus.core.navigation.DiagnosticsRoute
import app.sotreus.core.navigation.FriendsRoute
import app.sotreus.core.navigation.PlacesRoute
import app.sotreus.core.navigation.PrivacyRoute
import app.sotreus.core.navigation.ProfileRoute
import app.sotreus.core.navigation.ProofsRoute
import app.sotreus.core.navigation.SensorsRoute
import app.sotreus.core.navigation.SignaturesRoute
import app.sotreus.core.testing.FakeSotreusData
import app.sotreus.core.ui.Avatar
import app.sotreus.core.ui.CardStyle
import app.sotreus.core.ui.ChipTone
import app.sotreus.core.ui.MonoLabel
import app.sotreus.core.ui.NavRow
import app.sotreus.core.ui.ScreenColumn
import app.sotreus.core.ui.ScreenTitle
import app.sotreus.core.ui.SotreusCard
import app.sotreus.core.ui.StateChip
import app.sotreus.intelligence.SignatureClassifier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class SettingsUiState(
    val build: AppBuildInfo? = null,
    val solanaDevice: Boolean = false,
    val profile: LocalProfileEntity? = null,
    val wallet: LinkedIdentityEntity? = null,
    val settings: SotreusSettings = SotreusSettings(),
    val friends: Int = 0,
    val places: Int = 0,
    val stamped: Int = 0,
    val families: Int = 0,
    val wifiLimited: Boolean = false,
    val observing: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    build: AppBuildInfo,
    device: DeviceProfileRepository,
    profiles: ProfileRepository,
    settings: SettingsRepository,
    friends: FriendRepository,
    places: PlaceRepository,
    proofs: ProofRepository,
    pipeline: ObservationPipeline,
    classifier: SignatureClassifier,
) : ViewModel() {
    private val families = classifier.fleets.map { it.kind }.distinct().size

    val state: StateFlow<SettingsUiState> = combine(
        combine(device.profile, profiles.profile, profiles.wallet) { d, p, w -> Triple(d, p, w) },
        settings.settings,
        combine(friends.all, places.observePlaces(), proofs.observeBatches()) { f, p, b -> Triple(f.size, p.size, b.count { it.state == ProofState.FINALIZED }) },
        pipeline.snapshot,
    ) { (d, p, w), s, (f, pl, st), snap ->
        val wifiAge = snap.status.wifiLastFreshMs.takeIf { it > 0 }?.let { snap.atMs - it } ?: 0
        SettingsUiState(
            build = build, solanaDevice = d.isSolanaMobile, profile = p, wallet = w, settings = s, friends = f, places = pl, stamped = st,
            families = families,
            wifiLimited = snap.status.running && (snap.status.wifiLastRequestRejected || wifiAge > snap.status.wifiScanIntervalMs),
            observing = snap.observing,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())
}

@Composable
internal fun SettingsScreen(navigate: (Any) -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    SettingsContent(state, navigate)
}

/**
 * Settings root. The device decides the variant: G1 (local profile) everywhere, S1 (wallet card and
 * Proofs group) on Solana Mobile devices.
 */
@Composable
internal fun SettingsContent(state: SettingsUiState, navigate: (Any) -> Unit) {
    ScreenColumn {
        ScreenTitle(stringResource(R.string.feature_settings_title))
        ProfileCard(state, navigate)
        if (state.solanaDevice) {
            Group(stringResource(R.string.group_proofs)) {
                SotreusCard(style = CardStyle.RAISED, onClick = { navigate(ProofsRoute) }, padding = 14.dp) {
                    Row(Modifier.heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.xl)) {
                        Box(Modifier.size(34.dp).border(1.dp, SotreusTheme.colors.lineStrong, SotreusTheme.shapes.glyphWell), contentAlignment = Alignment.Center) {
                            Icon(SotreusIcons.ShieldCheck, contentDescription = null, tint = SotreusTheme.colors.textSoft, modifier = Modifier.size(18.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(R.string.row_proofs), style = SotreusTheme.typography.body, color = SotreusTheme.colors.text)
                            Text(stringResource(R.string.row_proofs_sub, stampingLabel(state.settings.stampingMode), state.stamped), style = SotreusTheme.typography.caption, color = SotreusTheme.colors.textMuted)
                        }
                        Icon(SotreusIcons.ChevronRight, contentDescription = null, tint = SotreusTheme.colors.textDim, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        Group(stringResource(R.string.group_people)) {
            NavRow(stringResource(R.string.row_friends), { navigate(FriendsRoute) }, value = state.friends.toString())
            NavRow(stringResource(R.string.row_presence), { navigate(FriendsRoute) }, value = stringResource(if (state.settings.nearbyPresence) R.string.on else R.string.off), showDivider = false)
        }
        Group(stringResource(R.string.group_awareness)) {
            NavRow(stringResource(R.string.row_places), { navigate(PlacesRoute) }, value = state.places.toString())
            NavRow(
                stringResource(R.string.row_sensors),
                { navigate(SensorsRoute) },
                value = stringResource(
                    when {
                        state.wifiLimited -> R.string.row_sensors_limited
                        state.observing -> R.string.row_sensors_ok
                        else -> R.string.row_sensors_paused
                    },
                ),
                valueAccent = state.wifiLimited,
            )
            NavRow(stringResource(R.string.row_signatures), { navigate(SignaturesRoute) }, value = stringResource(R.string.row_signatures_value, state.families))
            NavRow(
                stringResource(R.string.row_context_sources),
                { navigate(ContextSourcesRoute) },
                value = stringResource(if (state.settings.contextEnabled) R.string.on else R.string.off),
            )
            NavRow(
                stringResource(R.string.row_privacy),
                { navigate(PrivacyRoute) },
                value = stringResource(
                    when (state.settings.retention) {
                        RetentionPolicy.KEEP_30_DAYS -> R.string.retention_30
                        RetentionPolicy.KEEP_ALL -> R.string.retention_all
                        RetentionPolicy.KEEP_TAGGED_EXPIRE_REST -> R.string.retention_tagged
                    },
                ),
                showDivider = false,
            )
        }
        Group(stringResource(R.string.group_app)) {
            NavRow(stringResource(R.string.row_diagnostics), { navigate(DiagnosticsRoute) })
            NavRow(stringResource(R.string.row_about), { navigate(AboutRoute) }, showDivider = false)
        }
        Spacer(Modifier.weight(1f))
        state.build?.let { b ->
            val distribution = if (state.solanaDevice) Distribution.SOLANA_MOBILE else b.distribution
            MonoLabel(stringResource(R.string.feature_settings_footer, b.versionName, distribution.flavorName.uppercase(), b.databaseSchemaVersion), small = true)
        }
    }
}

@Composable
internal fun stampingLabel(mode: StampingMode) = stringResource(
    when (mode) {
        StampingMode.OFF -> R.string.stamping_off
        StampingMode.MANUAL_ONLY -> R.string.stamping_manual
        StampingMode.ASK_END_JOURNEY -> R.string.stamping_journey
        StampingMode.ASK_END_SIT -> R.string.stamping_sit
    },
)

@Composable
private fun Group(label: String, content: @Composable () -> Unit) {
    Column {
        MonoLabel(label, Modifier.padding(bottom = SotreusTheme.spacing.xs))
        content()
    }
}

@Composable
private fun ProfileCard(state: SettingsUiState, navigate: (Any) -> Unit) {
    val c = SotreusTheme.colors
    val name = state.profile?.displayName
    val wallet = state.wallet
    SotreusCard(style = CardStyle.OUTLINE_STRONG, large = true, onClick = { navigate(ProfileRoute) }, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (name != null) {
                Avatar(name, size = 48.dp, serif = true)
            } else {
                Box(Modifier.size(48.dp).border(1.dp, c.textDim, SotreusTheme.shapes.pill), contentAlignment = Alignment.Center) {
                    Icon(SotreusIcons.Person, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(SotreusTheme.sizes.iconSize))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(name ?: stringResource(R.string.profile_local), style = SotreusTheme.typography.bodyL.copy(fontWeight = FontWeight.Medium), color = c.text)
                when {
                    !state.solanaDevice -> Text(stringResource(R.string.profile_local_sub), style = SotreusTheme.typography.bodyS, color = c.textMuted)
                    wallet != null -> Text(stringResource(R.string.profile_wallet_line, Base58.abbreviate(wallet.publicKey)), style = SotreusTheme.typography.monoValue, color = c.textMuted)
                    else -> Text(stringResource(R.string.profile_wallet_none), style = SotreusTheme.typography.bodyS, color = c.textMuted)
                }
            }
            when {
                state.solanaDevice && wallet != null -> StateChip(stringResource(R.string.mainnet), ChipTone.DASHED, small = false)
                state.solanaDevice -> StateChip(stringResource(R.string.profile_connect), ChipTone.TEXT, small = false)
                else -> StateChip(stringResource(R.string.profile_edit), ChipTone.TEXT, small = false)
            }
        }
    }
}

@Preview(widthDp = 390, heightDp = 960, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun SettingsGenericPreview() {
    SotreusTheme {
        SettingsContent(
            SettingsUiState(build = AppBuildInfo("1.0.0", 1, "com.sotreus.app", "debug", Distribution.GENERIC, 1), friends = 3, places = 4, families = 19, wifiLimited = true),
        ) {}
    }
}

@Preview(widthDp = 390, heightDp = 1000, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun SettingsSolanaPreview() {
    SotreusTheme {
        SettingsContent(
            SettingsUiState(
                build = AppBuildInfo("1.0.0", 1, "com.sotreus.app", "debug", Distribution.SOLANA_MOBILE, 1),
                solanaDevice = true,
                profile = LocalProfileEntity("p", "Night owl", "nightowl", null, 0),
                wallet = LinkedIdentityEntity(1, app.sotreus.core.model.LinkedIdentityKind.SOLANA_WALLET, FakeSotreusData.WALLET, app.sotreus.core.model.SolanaCluster.MAINNET_BETA, "Seed Vault", true, 0),
                friends = 3, places = 4, stamped = 3, families = 19, wifiLimited = true,
            ),
        ) {}
    }
}
