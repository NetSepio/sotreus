package app.sotreus.feature.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sotreus.core.data.export.ExportService
import app.sotreus.core.data.field.LanguageRepository
import app.sotreus.core.data.field.readImport
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.ui.*
import app.sotreus.intelligence.fieldwatch.BundledLanguages
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LanguagesViewModel @Inject constructor(
    val languages: LanguageRepository,
    private val exports: ExportService,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val error = MutableStateFlow<String?>(null)
    fun select(tag: String) = viewModelScope.launch { runCatching { languages.select(tag) }.onFailure { error.value = it.message } }
    fun import(uri: Uri) = viewModelScope.launch { runCatching { languages.import(readImport(context, uri, 524_288)) }.onFailure { error.value = it.message } }
    fun template(ready: (Uri) -> Unit) = viewModelScope.launch {
        runCatching { exports.writeText("language-template.json", languages.template()) }.onSuccess(ready).onFailure { error.value = it.message }
    }
}

@Composable
internal fun LanguagesScreen(onBack: () -> Unit, vm: LanguagesViewModel = hiltViewModel()) {
    val state by vm.languages.state.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::import) }
    ScreenColumn(top = SotreusTheme.spacing.screenH) {
        BackTopBar(onBack = onBack)
        ScreenTitle(stringResource(R.string.languages_title), lead = stringResource(R.string.languages_lead))
        InfoRow(state.name, subtitle = stringResource(R.string.languages_count, state.count))
        error?.let { WarningBanner(it) }
        GhostButton(stringResource(R.string.languages_import), { picker.launch(arrayOf("application/json", "text/plain")) })
        GhostButton(stringResource(R.string.languages_template), { vm.template { shareFile(context, it, "Sotreus language template") } })
        NavRow("English", { vm.select("en") }, value = if (state.tag == "en") "✓" else null)
        BundledLanguages.all.sortedBy { it.english }.forEach { pack ->
            NavRow("${pack.english} · ${pack.name}", { vm.select(pack.tag) }, value = if (state.tag == pack.tag) "✓" else null)
        }
    }
}
