package app.sotreus.feature.context

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import app.sotreus.context.environment.AqiScale
import app.sotreus.context.environment.EnvironmentRepository
import app.sotreus.context.environment.EnvironmentState
import app.sotreus.context.environment.FeedState
import app.sotreus.context.environment.WarningRegion
import app.sotreus.context.environment.WarningRegions
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.ui.CardStyle
import app.sotreus.core.ui.SotreusCard
import app.sotreus.core.model.AirQualityMode
import app.sotreus.core.model.AirReading
import app.sotreus.core.model.HazardLevel
import app.sotreus.core.model.Provenance
import app.sotreus.core.navigation.ContextSourcesRoute
import app.sotreus.core.ui.CaveatBox
import app.sotreus.core.ui.EmptyHint
import app.sotreus.core.ui.InfoRow
import app.sotreus.core.ui.KeyValueTable
import app.sotreus.core.ui.LightButton
import app.sotreus.core.ui.MonoLabel
import app.sotreus.core.ui.RowDivider
import app.sotreus.core.ui.SectionHeader
import app.sotreus.core.ui.Stat
import app.sotreus.core.ui.StatTiles
import app.sotreus.core.ui.WarningBanner
import app.sotreus.core.ui.ageShort
import app.sotreus.core.ui.clockTime
import app.sotreus.core.ui.dayLabel
import kotlin.math.roundToInt

/**
 * Air quality, earthquakes, disasters, official warnings and station readings near the context
 * location (architecture handoff §14.5). Only what a source returned is drawn: while nothing has
 * arrived a loading card lists the sources, and sources with nothing to show are named in one line.
 */
@Composable
internal fun EnvironmentTab(env: EnvironmentState, now: Long, navigate: (Any) -> Unit, masked: Boolean = true) {
    if (!env.anyOn) {
        EmptyHint(stringResource(R.string.env_off_kicker), stringResource(R.string.env_off_body))
        LightButton(stringResource(R.string.ctx_open_sources), { navigate(ContextSourcesRoute) }, Modifier.fillMaxWidth())
        return
    }
    val uri = LocalUriHandler.current
    val sources = sourceRows(env)
    val pending = sources.filter { it.status == SourceStatus.PENDING }
    if (sources.none { it.status == SourceStatus.SHOWN }) {
        if (pending.isNotEmpty()) {
            LoadingSources(sources)
            return
        }
    } else {
        AreaLine(env, masked)
        if (pending.isNotEmpty()) StillFetching(pending.map { it.name })
    }

    // Official warnings first: the issuers' own words, for the region this phone is in.
    (env.nws as? FeedState.Ready)?.takeIf { it.value.isNotEmpty() }?.let { nws ->
        val region = (env.warningRegion as? WarningRegion.Nws)?.let { WarningRegions.nwsName(it.area) ?: it.area }.orEmpty()
        SectionHeader(stringResource(R.string.env_nws_header, region), accent = true) { ProvenanceChip(Provenance.NETWORK) }
        Banners(nws, now, EnvironmentRepository.WARNINGS_STALE_MS)
        Column {
            RowDivider(strong = true)
            nws.value.take(40).forEach { a ->
                InfoRow(
                    title = listOfNotNull(a.event, a.severity).joinToString(" · "),
                    subtitle = listOf(a.area, window(a.onsetMs, a.untilMs, now)).filter { it.isNotBlank() }.joinToString(" · "),
                )
            }
        }
        MonoLabel(stringResource(R.string.env_nws_caption, ageShort(now - nws.fetchedAtMs)), small = true)
    }

    (env.meteoAlarm as? FeedState.Ready)?.takeIf { it.value.isNotEmpty() }?.let { meteo ->
        val region = (env.warningRegion as? WarningRegion.MeteoAlarm)?.let { WarningRegions.meteoAlarmName(it.country) ?: it.country }.orEmpty()
        SectionHeader(stringResource(R.string.env_meteoalarm_header, region), accent = true) { ProvenanceChip(Provenance.NETWORK) }
        Banners(meteo, now, EnvironmentRepository.WARNINGS_STALE_MS)
        Column {
            RowDivider(strong = true)
            meteo.value.take(40).forEach { g ->
                val shown = g.areas.take(3).joinToString(", ")
                val areas = if (g.areas.size > 3) stringResource(R.string.env_meteoalarm_more, shown, g.areas.size - 3) else shown
                InfoRow(
                    title = stringResource(R.string.env_meteoalarm_title, g.level ?: g.severity.orEmpty(), g.event),
                    subtitle = listOf(areas, window(g.onsetMs, g.untilMs, now)).filter { it.isNotBlank() }.joinToString(" · "),
                    onClick = g.url?.let { u -> { runCatching { uri.openUri(u) } } },
                )
            }
        }
        MonoLabel(stringResource(R.string.env_meteoalarm_caption, ageShort(now - meteo.fetchedAtMs)), small = true)
    }

    (env.hazards as? FeedState.Ready)?.takeIf { it.value.isNotEmpty() }?.let { hazards ->
        SectionHeader(stringResource(R.string.env_hazards_header, hazards.value.size), accent = true) { ProvenanceChip(Provenance.NETWORK) }
        Banners(hazards, now, EnvironmentRepository.HAZARDS_STALE_MS)
        Column {
            RowDivider(strong = true)
            hazards.value.forEach { (h, d) ->
                InfoRow(
                    title = h.name,
                    subtitle = stringResource(R.string.env_hazard_row, levelName(h.level), "${dayLabel(h.fromMs, now)} ${clockTime(h.fromMs)}", km(d)),
                    onClick = h.url?.let { u -> { runCatching { uri.openUri(u) } } },
                )
            }
        }
        MonoLabel(stringResource(R.string.env_hazards_caption, ageShort(now - hazards.fetchedAtMs)), small = true)
    }

    (env.quakes as? FeedState.Ready)?.takeIf { it.value.isNotEmpty() }?.let { quakes ->
        SectionHeader(stringResource(R.string.env_quakes_header, quakes.value.size, env.quakeRadiusKm)) { ProvenanceChip(Provenance.NETWORK) }
        Banners(quakes, now, EnvironmentRepository.QUAKES_STALE_MS)
        Column {
            RowDivider(strong = true)
            quakes.value.take(30).forEach { (q, d) ->
                InfoRow(
                    title = stringResource(R.string.env_quake_title, q.magnitude, q.place ?: stringResource(R.string.env_quake_unnamed)),
                    subtitle = stringResource(R.string.env_quake_row, ageShort(now - q.atMs), km(d)),
                    onClick = q.url?.let { u -> { runCatching { uri.openUri(u) } } },
                )
            }
        }
        MonoLabel(stringResource(R.string.env_quakes_caption, ageShort(now - quakes.fetchedAtMs)), small = true)
    }

    (env.air as? FeedState.Ready)?.let { air ->
        SectionHeader(stringResource(R.string.env_air_header)) { ProvenanceChip(Provenance.PREDICTED) }
        Banners(air, now, EnvironmentRepository.AIR_STALE_MS)
        AirBody(air.value, air.fetchedAtMs, env.airMode, now, env.regionCode)
    }

    (env.openAq as? FeedState.Ready)?.value?.let { station ->
        val openAq = env.openAq as FeedState.Ready
        SectionHeader(stringResource(R.string.env_openaq_header)) { ProvenanceChip(Provenance.NETWORK) }
        Banners(openAq, now, EnvironmentRepository.OPENAQ_STALE_MS)
        KeyValueTable(
            station.values.map { v -> v.parameter to stringResource(R.string.env_openaq_value, formatValue(v.value), v.units, ageShort(now - v.atMs)) },
            title = stringResource(R.string.env_openaq_station, station.name, km(station.distanceKm)),
        )
        val source = station.provider ?: station.name
        MonoLabel(
            station.attribution?.let { stringResource(R.string.env_openaq_caption_attr, source, ageShort(now - openAq.fetchedAtMs), it) }
                ?: stringResource(R.string.env_openaq_caption, source, ageShort(now - openAq.fetchedAtMs)),
            small = true,
        )
    }

    // Sources that answered with nothing to show, or could not answer, in one quiet line each.
    sources.filter { it.status == SourceStatus.NOTHING }.takeIf { it.isNotEmpty() }?.let { rows ->
        MonoLabel(stringResource(R.string.env_nothing_reported, rows.joinToString(" · ") { it.detail ?: it.name }), small = true)
    }
    sources.filter { it.status == SourceStatus.NOT_AVAILABLE }.takeIf { it.isNotEmpty() }?.let { rows ->
        MonoLabel(stringResource(R.string.env_not_available, rows.joinToString(" · ") { r -> r.detail?.let { "${r.name} ($it)" } ?: r.name }), small = true)
    }
    CaveatBox(stringResource(R.string.env_caveat_kicker), stringResource(R.string.env_caveat_body))
}

private enum class SourceStatus { PENDING, SHOWN, NOTHING, NOT_AVAILABLE }

private data class SourceRow(val name: String, val status: SourceStatus, val detail: String? = null)

/** Every switched-on source, and whether it has something to show yet. */
@Composable
private fun sourceRows(env: EnvironmentState): List<SourceRow> {
    val waitingForPlace = !env.located
    @Composable
    fun <T> row(name: String, state: FeedState<T>, nothing: String?, hasContent: (T) -> Boolean): SourceRow? = when (state) {
        FeedState.Off -> null
        FeedState.Loading -> SourceRow(name, SourceStatus.PENDING)
        FeedState.NoLocation -> SourceRow(name, if (waitingForPlace) SourceStatus.PENDING else SourceStatus.NOT_AVAILABLE, stringResource(R.string.env_why_no_location))
        FeedState.Unavailable -> SourceRow(name, SourceStatus.NOT_AVAILABLE, stringResource(R.string.env_why_unreachable))
        FeedState.NeedsKey -> SourceRow(name, SourceStatus.NOT_AVAILABLE, stringResource(R.string.env_why_needs_key))
        FeedState.KeyRejected -> SourceRow(name, SourceStatus.NOT_AVAILABLE, stringResource(R.string.env_why_key_rejected))
        is FeedState.Ready -> SourceRow(name, if (hasContent(state.value)) SourceStatus.SHOWN else SourceStatus.NOTHING, nothing)
    }
    val warnings = when {
        !env.warningsOn -> emptyList()
        waitingForPlace -> listOf(SourceRow(stringResource(R.string.env_src_warnings), SourceStatus.PENDING))
        env.location == null -> listOf(SourceRow(stringResource(R.string.env_src_warnings), SourceStatus.NOT_AVAILABLE, stringResource(R.string.env_why_no_location)))
        env.warningRegion == null -> listOf(SourceRow(stringResource(R.string.env_src_warnings), SourceStatus.NOT_AVAILABLE, stringResource(R.string.env_why_no_coverage)))
        else -> listOfNotNull(
            row(stringResource(R.string.env_src_nws), env.nws, stringResource(R.string.env_nothing_nws)) { it.isNotEmpty() },
            row(stringResource(R.string.env_src_meteoalarm), env.meteoAlarm, stringResource(R.string.env_nothing_meteoalarm)) { it.isNotEmpty() },
        )
    }
    return warnings + listOfNotNull(
        row(stringResource(R.string.env_src_hazards), env.hazards, stringResource(R.string.env_nothing_hazards)) { it.isNotEmpty() },
        row(stringResource(R.string.env_src_quakes), env.quakes, stringResource(R.string.env_nothing_quakes, env.quakeRadiusKm)) { it.isNotEmpty() },
        row(stringResource(R.string.env_src_air), env.air, null) { true },
        row(stringResource(R.string.env_src_openaq), env.openAq, stringResource(R.string.env_nothing_openaq)) { it != null },
    )
}

/** Shown until the first source has something to draw: each source and whether it has answered. */
@Composable
private fun LoadingSources(rows: List<SourceRow>) {
    val c = SotreusTheme.colors
    SotreusCard(style = CardStyle.SURFACE) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.l)) {
            Spinner()
            Text(stringResource(R.string.env_loading_title), style = SotreusTheme.typography.rowTitle, color = c.text)
        }
        Spacer(Modifier.height(SotreusTheme.spacing.m))
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = SotreusTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                Text(r.name, style = SotreusTheme.typography.bodyS, color = c.textSoft, modifier = Modifier.weight(1f))
                Text(
                    stringResource(if (r.status == SourceStatus.PENDING) R.string.env_loading_waiting else R.string.env_loading_done),
                    style = SotreusTheme.typography.monoLabelS,
                    color = if (r.status == SourceStatus.PENDING) c.textDim else c.textMuted,
                )
            }
        }
        Spacer(Modifier.height(SotreusTheme.spacing.s))
        Text(stringResource(R.string.env_loading_note), style = SotreusTheme.typography.caption, color = c.textDim)
    }
}

/**
 * Which area everything below is for: the saved place or the plus code of where the phone is (coarse
 * when coordinates are masked), where that location came from, and how far each source reaches.
 */
@Composable
private fun AreaLine(env: EnvironmentState, masked: Boolean) {
    val loc = env.location ?: return
    val code = app.sotreus.intelligence.PlusCode.areaLabel(loc.point.lat, loc.point.lon, masked)
    val where = when (loc.source) {
        app.sotreus.context.ContextLocation.Source.PLACE -> stringResource(R.string.env_area_place, loc.placeName.orEmpty(), code)
        app.sotreus.context.ContextLocation.Source.SESSION -> stringResource(R.string.env_area_session, code)
        app.sotreus.context.ContextLocation.Source.PHONE -> stringResource(R.string.env_area_phone, code)
    }
    // With coordinates masked, name the state and country but not the nearest town.
    val area = env.area?.let { a ->
        val town = a.town
        val wide = listOfNotNull(a.region, a.country).joinToString(", ").ifBlank { null }
        when {
            masked || town == null -> wide
            wide != null -> stringResource(R.string.env_area_near, town, wide)
            else -> stringResource(R.string.env_area_near_town, town)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.xs)) {
        area?.let { Text(it, style = SotreusTheme.typography.rowTitle, color = SotreusTheme.colors.text) }
        MonoLabel(where)
        MonoLabel(stringResource(R.string.env_area_reach, env.quakeRadiusKm), small = true)
    }
}

/** One line above the sections while slower sources are still answering. */
@Composable
private fun StillFetching(names: List<String>) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m)) {
        Spinner()
        MonoLabel(stringResource(R.string.env_still_fetching, names.joinToString(", ")), small = true)
    }
}

@Composable
private fun Spinner() = CircularProgressIndicator(
    modifier = Modifier.size(SotreusTheme.spacing.xxl),
    color = SotreusTheme.colors.accent,
    trackColor = SotreusTheme.colors.lineSubtle,
    strokeWidth = Dp(SotreusTheme.sizes.iconStroke),
)

@Composable
private fun window(onsetMs: Long?, untilMs: Long?, now: Long): String = when {
    untilMs == null -> ""
    onsetMs != null && onsetMs > now -> stringResource(R.string.env_from_until, "${dayLabel(onsetMs, now)} ${clockTime(onsetMs)}", "${dayLabel(untilMs, now)} ${clockTime(untilMs)}")
    else -> stringResource(R.string.env_until, "${dayLabel(untilMs, now)} ${clockTime(untilMs)}")
}

private fun formatValue(v: Double): String =
    if (v >= 10 || v == Math.floor(v)) v.roundToInt().toString() else String.format(java.util.Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')

/** Failed-refresh and stale banners above a ready feed. */
@Composable
private fun Banners(state: FeedState.Ready<*>, now: Long, staleAfterMs: Long) {
    val age = now - state.fetchedAtMs
    when {
        state.refreshFailed -> WarningBanner(stringResource(R.string.env_refresh_failed, ageShort(age)))
        age > staleAfterMs -> WarningBanner(stringResource(R.string.env_stale, ageShort(age)))
    }
}

@Composable
private fun AirBody(air: AirReading, fetchedAtMs: Long, mode: AirQualityMode, now: Long, regionCode: String?) {
    // One index, on the scale people use where the phone is; the other scale only if this one is missing.
    val scale = WarningRegions.aqiScale(regionCode)
    val eu = air.europeanAqi?.let { Stat(it.toString(), stringResource(eaqiBand(it)), kicker = stringResource(R.string.env_air_eu)) }
    val us = air.usAqi?.let { Stat(it.toString(), stringResource(usAqiBand(it)), kicker = stringResource(R.string.env_air_us)) }
    val shown = if (scale == AqiScale.EUROPEAN) eu ?: us else us ?: eu
    shown?.let { StatTiles(listOf(it)) }
    MonoLabel(
        stringResource(
            when {
                shown === eu -> R.string.env_air_scale_eu
                regionCode?.startsWith("US-") == true || regionCode in US_TERRITORIES -> R.string.env_air_scale_us
                else -> R.string.env_air_scale_us_elsewhere
            },
        ),
        small = true,
    )
    KeyValueTable(
        listOfNotNull(
            air.pm25?.let { stringResource(R.string.env_air_pm25) to stringResource(R.string.env_ugm3, it.roundToInt().toString()) },
            air.pm10?.let { stringResource(R.string.env_air_pm10) to stringResource(R.string.env_ugm3, it.roundToInt().toString()) },
            air.ozone?.let { stringResource(R.string.env_air_o3) to stringResource(R.string.env_ugm3, it.roundToInt().toString()) },
            air.no2?.let { stringResource(R.string.env_air_no2) to stringResource(R.string.env_ugm3, it.roundToInt().toString()) },
        ),
    )
    MonoLabel(
        stringResource(
            R.string.env_air_caption,
            "${dayLabel(air.modelTimeMs, now)} ${clockTime(air.modelTimeMs)}",
            ageShort(now - fetchedAtMs),
            stringResource(if (mode == AirQualityMode.FINE) R.string.env_air_fine else R.string.env_air_coarse),
        ),
        small = true,
    )
}

private val US_TERRITORIES = setOf("PR", "GU", "VI", "AS", "MP")

/** European Air Quality Index bands (EEA): 0–20, 20–40, 40–60, 60–80, 80–100, over 100. */
internal fun eaqiBand(v: Int) = when {
    v <= 20 -> R.string.env_eaqi_1
    v <= 40 -> R.string.env_eaqi_2
    v <= 60 -> R.string.env_eaqi_3
    v <= 80 -> R.string.env_eaqi_4
    v <= 100 -> R.string.env_eaqi_5
    else -> R.string.env_eaqi_6
}

/** US EPA AQI categories: 0–50, 51–100, 101–150, 151–200, 201–300, over 300. */
internal fun usAqiBand(v: Int) = when {
    v <= 50 -> R.string.env_usaqi_1
    v <= 100 -> R.string.env_usaqi_2
    v <= 150 -> R.string.env_usaqi_3
    v <= 200 -> R.string.env_usaqi_4
    v <= 300 -> R.string.env_usaqi_5
    else -> R.string.env_usaqi_6
}

@Composable
private fun levelName(l: HazardLevel) = stringResource(
    when (l) {
        HazardLevel.GREEN -> R.string.env_level_green
        HazardLevel.ORANGE -> R.string.env_level_orange
        HazardLevel.RED -> R.string.env_level_red
    },
)

@androidx.compose.ui.tooling.preview.Preview(widthDp = 390, heightDp = 2000, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun ContextEnvironmentPreview() {
    val fake = app.sotreus.core.testing.FakeSotreusData
    val now = fake.NOW
    val here = app.sotreus.context.ContextLocation(app.sotreus.context.GeoPoint(1.2834, 103.8607), app.sotreus.context.ContextLocation.Source.PLACE, "Office")
    val env = EnvironmentState(
        location = here,
        air = FeedState.Ready(fake.AIR, now - 20 * 60_000L, refreshFailed = false),
        airMode = AirQualityMode.COARSE,
        quakes = FeedState.Ready(fake.QUAKES, now - 4 * 60_000L, refreshFailed = false),
        hazards = FeedState.Ready(fake.HAZARDS, now - 22 * 60_000L, refreshFailed = false),
        warningsOn = true,
        warningRegion = WarningRegion.Nws("CA"),
        located = true,
        nws = FeedState.Ready(fake.NWS_ALERTS, now - 3 * 60_000L, refreshFailed = false),
        meteoAlarm = FeedState.Ready(fake.METEOALARM_GROUPS, now - 5 * 60_000L, refreshFailed = false),
        openAq = FeedState.Ready(fake.STATION, now - 30 * 60_000L, refreshFailed = false),
    )
    app.sotreus.core.designsystem.theme.SotreusTheme {
        ContextContent(ContextUiState(tab = ContextTab.ENVIRONMENT, environment = env, now = now), {}, {}, {})
    }
}
