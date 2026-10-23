package app.sotreus.feature.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sotreus.core.data.export.ExportService
import app.sotreus.core.data.field.SignatureCatalogRepository
import app.sotreus.core.data.field.readImport
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.ui.*
import app.sotreus.intelligence.SignatureClassifier
import app.sotreus.intelligence.fieldwatch.SignaturePack
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SignaturesViewModel @Inject constructor(
    val catalog: SignatureCatalogRepository,
    private val exports: ExportService,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val error = MutableStateFlow<String?>(null)
    val pending = MutableStateFlow<SignaturePack?>(null)
    private var pendingText = ""
    fun preview(uri: Uri) = viewModelScope.launch {
        error.value = null
        runCatching {
            val text = readImport(context, uri, SignatureCatalogRepository.MAX_BYTES)
            val pack = withContext(Dispatchers.Default) { catalog.preview(text) }
            pendingText = text; pending.value = pack
        }.onFailure { error.value = it.message ?: "Could not read signatures" }
    }
    fun apply() = viewModelScope.launch {
        runCatching { catalog.import(pendingText) }.onSuccess { cancel() }.onFailure { error.value = it.message }
    }
    fun cancel() { pendingText = ""; pending.value = null }
    fun restore() = viewModelScope.launch { runCatching { catalog.restore() }.onFailure { error.value = it.message } }
    fun export(ready: (Uri) -> Unit) = viewModelScope.launch {
        runCatching { withContext(Dispatchers.IO) { exports.writeText("signatures.json", catalog.export()) } }
            .onSuccess(ready).onFailure { error.value = it.message }
    }
}

@Composable
internal fun SignaturesScreen(onBack: () -> Unit, vm: SignaturesViewModel = hiltViewModel()) {
    val fleets by vm.catalog.fleets.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    var restoring by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::preview) }
    val families = fleets.groupBy { SignatureClassifier.familyOf(it.kind) }.entries.sortedByDescending { it.value.size }
    ScreenColumn(top = SotreusTheme.spacing.screenH) {
        BackTopBar(onBack = onBack)
        ScreenTitle(stringResource(R.string.signatures_title), lead = stringResource(R.string.signatures_lead))
        MonoLabel(stringResource(R.string.signatures_catalog, fleets.size))
        error?.let { WarningBanner(it) }
        GhostButton(stringResource(R.string.signatures_import), { picker.launch(arrayOf("application/json", "text/plain")) })
        GhostButton(stringResource(R.string.signatures_export), { vm.export { shareFile(context, it, "Sotreus signatures") } })
        GhostButton(stringResource(R.string.signatures_restore), { restoring = true })
        Column {
            families.forEach { (family, rows) ->
                InfoRow(familyName(family), subtitle = rows.map { it.name }.sorted().take(6).joinToString(", ") + if (rows.size > 6) "…" else "") {
                    Text(pluralStringResource(R.plurals.signature_rows, rows.size, rows.size), style = SotreusTheme.typography.monoLabelS, color = SotreusTheme.colors.textDim)
                }
            }
        }
    }
    if (pending != null) ConfirmDialog(stringResource(R.string.signatures_import_title),
        stringResource(R.string.signatures_import_body) + "\n" + stringResource(R.string.signatures_preview_count, pending!!.fleets.size, pending!!.catalogVersion),
        stringResource(R.string.signatures_import), stringResource(app.sotreus.core.ui.R.string.core_ui_cancel), { vm.apply() }, vm::cancel)
    if (restoring) ConfirmDialog(stringResource(R.string.signatures_restore), stringResource(R.string.signatures_restore_body),
        stringResource(R.string.signatures_restore), stringResource(app.sotreus.core.ui.R.string.core_ui_cancel),
        { restoring = false; vm.restore() }, { restoring = false })
}
