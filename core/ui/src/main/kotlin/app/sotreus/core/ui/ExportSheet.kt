package app.sotreus.core.ui

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
import androidx.compose.ui.res.stringResource
import app.sotreus.core.designsystem.theme.SotreusTheme

/** Choices on the export sheet. Defaults are privacy-reduced (handoff §25). */
data class ExportChoice(
    val exactCoordinates: Boolean = false,
    val rawIdentifiers: Boolean = false,
    val includeNotes: Boolean = false,
    val exactTimes: Boolean = false,
) {
    val raw: Boolean get() = exactCoordinates || rawIdentifiers || includeNotes || exactTimes
}

/** Export sheet (not designed): composed from switch rows, a warning banner and buttons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(onExport: (ExportChoice) -> Unit, onDismiss: () -> Unit) {
    var choice by remember { mutableStateOf(ExportChoice()) }
    val c = SotreusTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surfaceRaised,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = SotreusTheme.spacing.screenH)
                .padding(bottom = SotreusTheme.spacing.screenBottom).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m),
        ) {
            MonoLabel(stringResource(R.string.export_title))
            Text(stringResource(R.string.export_lead), style = SotreusTheme.typography.bodyS, color = c.textMuted)
            Column {
                SwitchRow(stringResource(R.string.export_exact_coords), choice.exactCoordinates, { choice = choice.copy(exactCoordinates = it) }, subtitle = stringResource(R.string.export_exact_coords_sub), bordered = false)
                RowDivider()
                SwitchRow(stringResource(R.string.export_raw_ids), choice.rawIdentifiers, { choice = choice.copy(rawIdentifiers = it) }, subtitle = stringResource(R.string.export_raw_ids_sub), bordered = false)
                RowDivider()
                SwitchRow(stringResource(R.string.export_notes), choice.includeNotes, { choice = choice.copy(includeNotes = it) }, bordered = false)
                RowDivider()
                SwitchRow(stringResource(R.string.export_exact_times), choice.exactTimes, { choice = choice.copy(exactTimes = it) }, subtitle = stringResource(R.string.export_exact_times_sub), bordered = false)
            }
            if (choice.raw) WarningBanner(stringResource(R.string.export_raw_warning))
            PrimaryButton(stringResource(if (choice.raw) R.string.export_action_raw else R.string.export_action), onClick = { onExport(choice) })
            QuietButton(stringResource(R.string.core_ui_cancel), onDismiss)
        }
    }
}
