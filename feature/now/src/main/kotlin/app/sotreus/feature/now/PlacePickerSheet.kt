package app.sotreus.feature.now

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.sotreus.core.data.repository.PlaceSummary
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.ui.ChipTone
import app.sotreus.core.ui.GhostButton
import app.sotreus.core.ui.InfoRow
import app.sotreus.core.ui.InlineLink
import app.sotreus.core.ui.MonoLabel
import app.sotreus.core.ui.StateChip

/** Place picker (not designed): composed from existing rows, chips and buttons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlacePickerSheet(
    places: List<PlaceSummary>,
    currentId: Long?,
    onSelect: (Long?) -> Unit,
    onCreate: (String, Boolean) -> Unit,
    onDetails: (Long) -> Unit,
    onDismiss: () -> Unit,
    byLocation: Boolean = false,
) {
    var creating by remember { mutableStateOf(false) }
    val c = SotreusTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surfaceRaised,
        contentColor = c.text,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = SotreusTheme.spacing.screenH).padding(bottom = SotreusTheme.spacing.screenBottom).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m),
        ) {
            MonoLabel(stringResource(R.string.picker_title))
            InfoRow(
                title = stringResource(R.string.picker_unsaved),
                onClick = { onSelect(null) },
                trailing = { if (currentId == null) StateChip("✓", ChipTone.ACCENT_FILLED) },
            )
            places.forEach { p ->
                InfoRow(
                    title = p.place.name,
                    subtitle = pluralStringResource(R.plurals.picker_visits, p.visits, p.visits),
                    onClick = { onSelect(p.place.id) },
                    trailing = {
                        if (p.place.id == currentId) {
                            InlineLink(stringResource(R.string.picker_details), onClick = { onDetails(p.place.id) })
                            StateChip("✓", ChipTone.ACCENT_FILLED)
                        }
                    },
                )
            }
            GhostButton(stringResource(R.string.picker_new), onClick = { creating = true }, strong = true)
            Text(stringResource(R.string.picker_note), style = SotreusTheme.typography.caption, color = c.textDim)
            if (byLocation) Text(stringResource(R.string.picker_note_location), style = SotreusTheme.typography.caption, color = c.textDim)
        }
    }
    if (creating) NewPlaceDialog(onCreate = { name, withLocation -> creating = false; onCreate(name, withLocation) }, onDismiss = { creating = false })
}

/** New place: a name, and optionally this phone's current location (asked for in context). */
@Composable
private fun NewPlaceDialog(onCreate: (String, Boolean) -> Unit, onDismiss: () -> Unit) {
    val c = SotreusTheme.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    fun granted() = listOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION).any {
        androidx.core.content.ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    var name by remember { mutableStateOf("") }
    var withLocation by remember { mutableStateOf(granted()) }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { r ->
        withLocation = r.values.any { it }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surfaceRaised,
        shape = SotreusTheme.shapes.featureCard,
        title = { Text(stringResource(R.string.picker_new_title), style = SotreusTheme.typography.titleS.copy(fontSize = SotreusTheme.typography.titleS.fontSize * 0.8f), color = c.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m)) {
                app.sotreus.core.ui.PillField(name, { name = it }, stringResource(R.string.picker_new_hint), stringResource(R.string.picker_name_label))
                app.sotreus.core.ui.SwitchRow(
                    stringResource(R.string.picker_save_location),
                    withLocation,
                    { on ->
                        if (on && !granted()) permission.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
                        else withLocation = on
                    },
                    subtitle = stringResource(R.string.picker_save_location_sub),
                    bordered = false,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onCreate(name.trim(), withLocation) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.picker_create), style = SotreusTheme.typography.button, color = if (name.isNotBlank()) c.accent else c.textDim)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.picker_cancel), style = SotreusTheme.typography.button, color = c.textMuted) }
        },
    )
}
