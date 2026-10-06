package app.sotreus.feature.settings

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.sotreus.core.navigation.DiagnosticsRoute
import app.sotreus.core.navigation.PrivacyRoute
import app.sotreus.core.navigation.ProfileRoute
import app.sotreus.core.navigation.SensorsRoute
import app.sotreus.core.navigation.SettingsRoute
import app.sotreus.core.navigation.SignaturesRoute
import app.sotreus.core.navigation.SotreusNavigator

fun NavGraphBuilder.settingsGraph(nav: SotreusNavigator) {
    composable<SettingsRoute> { SettingsScreen(navigate = nav::navigate) }
    composable<SensorsRoute> { SensorsScreen(onBack = nav::back) }
    composable<DiagnosticsRoute> { DiagnosticsScreen(onBack = nav::back) }
    composable<SignaturesRoute> { SignaturesScreen(onBack = nav::back) }
    composable<PrivacyRoute> { PrivacyScreen(onBack = nav::back) }
    composable<ProfileRoute> { ProfileScreen(navigate = nav::navigate, onBack = nav::back) }
}
