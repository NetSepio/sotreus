package app.sotreus.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.ui.BackTopBar
import app.sotreus.core.ui.CaveatBox
import app.sotreus.core.ui.ScreenColumn
import app.sotreus.core.ui.ScreenTitle

/** About & limitations (no mock): composed from ScreenTitle and caveat boxes only. */
@Composable
internal fun AboutScreen(onBack: () -> Unit) {
    ScreenColumn(top = SotreusTheme.spacing.screenH) {
        BackTopBar(onBack = onBack)
        ScreenTitle(stringResource(R.string.about_title), lead = stringResource(R.string.about_lead))
        CaveatBox(stringResource(R.string.about_can_kicker), stringResource(R.string.about_can_body))
        CaveatBox(stringResource(R.string.about_cannot_kicker), stringResource(R.string.about_cannot_body))
        CaveatBox(stringResource(R.string.about_strength_kicker), stringResource(R.string.about_strength_body))
        CaveatBox(stringResource(R.string.about_identity_kicker), stringResource(R.string.about_identity_body))
        CaveatBox(stringResource(R.string.about_privacy_kicker), stringResource(R.string.about_privacy_body))
        CaveatBox(stringResource(R.string.about_credits_kicker), stringResource(R.string.about_credits_body))
    }
}

@Preview(widthDp = 390, heightDp = 1000, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun AboutPreview() {
    SotreusTheme { AboutScreen(onBack = {}) }
}
