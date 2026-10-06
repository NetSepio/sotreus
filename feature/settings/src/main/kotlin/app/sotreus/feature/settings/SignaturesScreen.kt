package app.sotreus.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.model.DeviceFamily
import app.sotreus.core.ui.BackTopBar
import app.sotreus.core.ui.InfoRow
import app.sotreus.core.ui.MonoLabel
import app.sotreus.core.ui.ScreenColumn
import app.sotreus.core.ui.ScreenTitle
import app.sotreus.core.ui.familyName
import app.sotreus.intelligence.SignatureClassifier
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SignaturesViewModel @Inject constructor(classifier: SignatureClassifier) : ViewModel() {
    /** Family → vendor/product rows in the catalog. */
    val families: List<Pair<DeviceFamily, List<String>>> = classifier.fleets
        .groupBy { SignatureClassifier.familyOf(it.kind) }
        .map { (family, fleets) -> family to fleets.map { it.name }.sorted() }
        .sortedByDescending { it.second.size }
    val total = classifier.catalogSize
}

/** Signature catalog (not designed): families as rows, composed from existing components. */
@Composable
internal fun SignaturesScreen(onBack: () -> Unit, vm: SignaturesViewModel = hiltViewModel()) {
    ScreenColumn(top = SotreusTheme.spacing.screenH) {
        BackTopBar(onBack = onBack)
        ScreenTitle(stringResource(R.string.signatures_title), lead = stringResource(R.string.signatures_lead))
        Column {
            MonoLabel(stringResource(R.string.signatures_source, vm.total))
            vm.families.forEach { (family, names) ->
                InfoRow(
                    title = familyName(family),
                    subtitle = names.take(6).joinToString(", ") + if (names.size > 6) "…" else "",
                ) { Text(pluralStringResource(R.plurals.signature_rows, names.size, names.size), style = SotreusTheme.typography.monoLabelS, color = SotreusTheme.colors.textDim) }
            }
        }
    }
}
