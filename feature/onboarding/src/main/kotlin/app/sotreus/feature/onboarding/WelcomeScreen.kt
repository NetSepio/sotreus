package app.sotreus.feature.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sotreus.core.data.repository.ProfileRepository
import app.sotreus.core.designsystem.icon.SotreusLogo
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.ui.GhostButton
import app.sotreus.core.ui.PrimaryButton
import app.sotreus.core.ui.RowDivider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WelcomeViewModel @Inject constructor(private val profiles: ProfileRepository) : ViewModel() {
    fun startLocalOnly(done: () -> Unit) {
        viewModelScope.launch {
            profiles.startLocalOnly()
            done()
        }
    }
}

@Composable
internal fun WelcomeScreen(onStarted: () -> Unit, onWhatCanSee: () -> Unit, vm: WelcomeViewModel = hiltViewModel()) {
    WelcomeContent(onStart = { vm.startLocalOnly(onStarted) }, onWhatCanSee = onWhatCanSee)
}

/** Screen 01. Identical in every build; never implies sign-in or a wallet is needed. */
@Composable
internal fun WelcomeContent(onStart: () -> Unit, onWhatCanSee: () -> Unit) {
    val c = SotreusTheme.colors
    val t = SotreusTheme.typography
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Decorative orbit rings, top-right (aria-hidden in the mock).
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width + 40.dp.toPx(), 60.dp.toPx())
            drawCircle(c.lineNav, 210.dp.toPx(), center, style = Stroke(1.dp.toPx()))
            drawCircle(c.line, 140.dp.toPx(), center, style = Stroke(1.dp.toPx()))
            drawCircle(c.accent.copy(alpha = 0.35f), 70.dp.toPx(), center, style = Stroke(1.dp.toPx()))
        }
        Column(
            Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(start = 24.dp, end = 24.dp, top = 56.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.xl)) {
                SotreusLogo()
                Text(stringResource(R.string.onb_wordmark), style = t.monoLabel.copy(fontWeight = FontWeight.Medium, fontSize = t.monoValue.fontSize * 1.17f, letterSpacing = 0.3.em), color = c.text)
            }
            Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.xxl)) {
                Text(
                    buildAnnotatedString {
                        append(stringResource(R.string.onb_headline_1))
                        append("\n")
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = c.textMuted)) { append(stringResource(R.string.onb_headline_2)) }
                    },
                    style = t.displayL,
                    color = c.text,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(R.string.onb_lead), style = t.bodyL, color = c.textMuted)
            }
            Column {
                RowDivider(strong = true)
                Principle(stringResource(R.string.onb_local_first), stringResource(R.string.onb_local_first_body))
                Principle(stringResource(R.string.onb_receive_only), stringResource(R.string.onb_receive_only_body))
                Principle(stringResource(R.string.onb_evidence), stringResource(R.string.onb_evidence_body))
            }
            Spacer(Modifier.weight(1f))
            Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.xl)) {
                PrimaryButton(stringResource(R.string.onb_start), onStart)
                GhostButton(stringResource(R.string.onb_what_can_see), onWhatCanSee)
                Text(
                    stringResource(R.string.onb_no_signin),
                    style = t.bodyS,
                    color = c.textDim,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun Principle(kicker: String, body: String) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.xxl)) {
            Box(Modifier.width(92.dp).padding(top = 2.dp)) {
                Text(kicker.uppercase(), style = SotreusTheme.typography.monoLabel.copy(letterSpacing = 0.12.em), color = SotreusTheme.colors.accent)
            }
            Text(body, style = SotreusTheme.typography.body.copy(fontSize = SotreusTheme.typography.rowTitle.fontSize * 0.965f), color = SotreusTheme.colors.textSoft)
        }
        RowDivider(strong = true)
    }
}

@Preview(widthDp = 390, heightDp = 844, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun WelcomePreview() {
    SotreusTheme { WelcomeContent(onStart = {}, onWhatCanSee = {}) }
}
