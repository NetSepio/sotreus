package app.sotreus.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.sotreus.core.model.Confidence
import app.sotreus.core.model.DeviceFamily
import app.sotreus.core.model.PresenceState
import app.sotreus.core.model.RadioKind
import app.sotreus.core.model.UserEntityState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// Display labels shared by every screen. All copy comes from strings.xml.

@Composable
@ReadOnlyComposable
fun familyName(family: DeviceFamily): String = stringResource(
    when (family) {
        DeviceFamily.FINDER_TAG -> R.string.family_finder_tag
        DeviceFamily.BEACON -> R.string.family_beacon
        DeviceFamily.SIGNAGE -> R.string.family_signage
        DeviceFamily.WEARABLE -> R.string.family_wearable
        DeviceFamily.CAMERA -> R.string.family_camera
        DeviceFamily.DRONE -> R.string.family_drone
        DeviceFamily.TEST_TOOL -> R.string.family_test_tool
        DeviceFamily.PUBLIC_SAFETY -> R.string.family_public_safety
        DeviceFamily.VEHICLE -> R.string.family_vehicle
        DeviceFamily.GLASSES -> R.string.family_glasses
        DeviceFamily.AUDIO -> R.string.family_audio
        DeviceFamily.THERMOSTAT -> R.string.family_thermostat
        DeviceFamily.ACCESS_CONTROL -> R.string.family_access_control
        DeviceFamily.HEALTH -> R.string.family_health
        DeviceFamily.SMART_HOME -> R.string.family_smart_home
        DeviceFamily.ROUTER -> R.string.family_router
        DeviceFamily.MESH_RADIO -> R.string.family_mesh_radio
        DeviceFamily.PHONE_PC -> R.string.family_phone_pc
        DeviceFamily.OTHER -> R.string.family_other
    },
)

/** Adjective form used in "Camera-family signature", "Finder-tag-family advertiser". */
@Composable
@ReadOnlyComposable
fun familyAdjective(family: DeviceFamily): String = stringResource(
    when (family) {
        DeviceFamily.FINDER_TAG -> R.string.family_adj_finder_tag
        DeviceFamily.BEACON -> R.string.family_adj_beacon
        DeviceFamily.SIGNAGE -> R.string.family_adj_signage
        DeviceFamily.WEARABLE -> R.string.family_adj_wearable
        DeviceFamily.CAMERA -> R.string.family_adj_camera
        DeviceFamily.DRONE -> R.string.family_adj_drone
        DeviceFamily.TEST_TOOL -> R.string.family_adj_test_tool
        DeviceFamily.PUBLIC_SAFETY -> R.string.family_adj_public_safety
        DeviceFamily.VEHICLE -> R.string.family_adj_vehicle
        DeviceFamily.GLASSES -> R.string.family_adj_glasses
        DeviceFamily.AUDIO -> R.string.family_adj_audio
        DeviceFamily.THERMOSTAT -> R.string.family_adj_thermostat
        DeviceFamily.ACCESS_CONTROL -> R.string.family_adj_access_control
        DeviceFamily.HEALTH -> R.string.family_adj_health
        DeviceFamily.SMART_HOME -> R.string.family_adj_smart_home
        DeviceFamily.ROUTER -> R.string.family_adj_router
        DeviceFamily.MESH_RADIO -> R.string.family_adj_mesh_radio
        DeviceFamily.PHONE_PC -> R.string.family_adj_phone_pc
        DeviceFamily.OTHER -> R.string.family_adj_other
    },
)

@Composable
@ReadOnlyComposable
fun familySignature(family: DeviceFamily): String = stringResource(R.string.family_signature, familyAdjective(family))

@Composable
@ReadOnlyComposable
fun confidenceLabel(c: Confidence): String = stringResource(
    when (c) {
        Confidence.HIGH -> R.string.confidence_high
        Confidence.MEDIUM -> R.string.confidence_medium
        Confidence.LOW -> R.string.confidence_low
    },
)

@Composable
@ReadOnlyComposable
fun userLabel(state: UserEntityState): String = stringResource(
    when (state) {
        UserEntityState.UNCLASSIFIED -> R.string.label_unclassified
        UserEntityState.MINE -> R.string.label_mine
        UserEntityState.EXPECTED -> R.string.label_expected
        UserEntityState.TAGGED -> R.string.label_tagged
        UserEntityState.WATCH -> R.string.label_watch
        UserEntityState.IGNORE -> R.string.label_ignore
    },
)

/** Calm title for an entity: your name for it, its advertised name, else a family description. */
@Composable
@ReadOnlyComposable
fun entityTitle(userName: String?, advertisedName: String?, kind: RadioKind, family: DeviceFamily?): String = when {
    !userName.isNullOrBlank() -> userName
    !advertisedName.isNullOrBlank() -> advertisedName
    kind == RadioKind.WIFI -> stringResource(R.string.entity_hidden_network)
    family != null && family != DeviceFamily.OTHER -> stringResource(R.string.family_advertiser, familyAdjective(family))
    else -> stringResource(R.string.entity_unnamed_advertiser)
}

/** Glyph for an entity in lists and on the Bands canvas (HANDOFF_V1_UI.md §3.4). */
fun glyphFor(kind: RadioKind, state: UserEntityState, presence: PresenceState, stale: Boolean, attention: Boolean): GlyphSpec {
    val tagged = state == UserEntityState.TAGGED || state == UserEntityState.WATCH
    val shape = when {
        tagged -> GlyphShape.TAGGED
        kind == RadioKind.WIFI -> GlyphShape.WIFI
        else -> GlyphShape.BLE
    }
    val tone = when {
        attention -> GlyphTone.ATTENTION
        tagged || state == UserEntityState.MINE -> GlyphTone.TAGGED
        presence == PresenceState.NEW || presence == PresenceState.SEEN_ELSEWHERE -> GlyphTone.NEW
        else -> GlyphTone.FAMILIAR
    }
    return GlyphSpec(shape, tone, stale)
}

/** "2 s", "38 s", "4 min", "3 h", "2 d". */
@Composable
@ReadOnlyComposable
fun ageShort(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> stringResource(R.string.age_seconds, s.toInt())
        s < 3600 -> stringResource(R.string.age_minutes, (s / 60).toInt())
        s < 86_400 -> stringResource(R.string.age_hours, (s / 3600).toInt())
        else -> stringResource(R.string.age_days, (s / 86_400).toInt())
    }
}

@Composable
@ReadOnlyComposable
fun ageAgo(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> stringResource(R.string.age_seconds_ago, s.toInt())
        s < 3600 -> stringResource(R.string.age_minutes_ago, (s / 60).toInt())
        else -> stringResource(R.string.age_hours_ago, (s / 3600).toInt())
    }
}

/** "1 h 08 min", "42 min", "18 s". */
@Composable
@ReadOnlyComposable
fun durationLabel(ms: Long): String {
    val totalMin = ms / 60_000
    return when {
        totalMin >= 60 -> stringResource(R.string.duration_h_min, (totalMin / 60).toInt(), (totalMin % 60).toInt())
        totalMin >= 1 -> stringResource(R.string.duration_min, totalMin.toInt())
        else -> stringResource(R.string.duration_s, (ms / 1000).toInt())
    }
}

/** "Today", "Yesterday", "Thu", or "28 Sep" for older dates. */
@Composable
@ReadOnlyComposable
fun dayLabel(atMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val zone = ZoneId.systemDefault()
    val d = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return when {
        d == today -> stringResource(R.string.today)
        d == today.minusDays(1) -> stringResource(R.string.yesterday)
        d.isAfter(today.minusDays(7)) -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
        else -> d.format(DateTimeFormatter.ofPattern("d MMM", locale))
    }
}

fun clockTime(atMs: Long): String =
    Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))

fun dateTime(atMs: Long): String =
    Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm:ss", Locale.getDefault()))

fun dateShort(atMs: Long): String =
    Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))

fun isSameDay(a: Long, b: Long): Boolean {
    val z = ZoneId.systemDefault()
    return Instant.ofEpochMilli(a).atZone(z).toLocalDate() == Instant.ofEpochMilli(b).atZone(z).toLocalDate()
}

@Composable
fun seenAtPlaces(n: Int): String = pluralStringResource(R.plurals.entity_seen_at_places, n, n)

/** Standard state chip for an entity in lists. */
@Composable
fun EntityStateChip(state: UserEntityState, presence: PresenceState, stale: Boolean, simulated: Boolean = false) {
    when {
        stale -> StateChip(stringResource(R.string.chip_stale), ChipTone.DASHED)
        state == UserEntityState.TAGGED || state == UserEntityState.WATCH -> StateChip(stringResource(R.string.chip_tagged), ChipTone.TEXT)
        state == UserEntityState.MINE -> StateChip(stringResource(R.string.chip_mine), ChipTone.NEUTRAL)
        presence == PresenceState.NEW -> StateChip(stringResource(R.string.chip_new), ChipTone.ACCENT)
        presence == PresenceState.SEEN_ELSEWHERE -> StateChip(stringResource(R.string.chip_seen_elsewhere), ChipTone.ACCENT)
        else -> StateChip(stringResource(R.string.chip_familiar), ChipTone.NEUTRAL)
    }
    if (simulated) StateChip(stringResource(R.string.chip_simulated), ChipTone.DASHED)
}
