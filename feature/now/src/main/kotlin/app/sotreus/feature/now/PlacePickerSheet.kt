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
import app.sotreus.core.ui.TextInputDialog

/** Place picker (not designed): composed from existing rows, chips and buttons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlacePickerSheet(
    places: List<PlaceSummary>,
    currentId: Long?,
    onSelect: (Long?) -> Unit,
    onCreate: (String) -> Unit,
    onDetails: (Long) -> Unit,
    onDismiss: () -> Unit,
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
        }
    }
    if (creating) {
        TextInputDialog(
            title = stringResource(R.string.picker_new_title),
            initial = "",
            placeholder = stringResource(R.string.picker_new_hint),
            confirm = stringResource(R.string.picker_create),
            dismiss = stringResource(R.string.picker_cancel),
            onConfirm = { creating = false; onCreate(it) },
            onDismiss = { creating = false },
        )
    }
}
