package app.sotreus.feature.onboarding

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.sotreus.core.navigation.AboutRoute
import app.sotreus.core.navigation.NowRoute
import app.sotreus.core.navigation.PermissionsRoute
import app.sotreus.core.navigation.SotreusNavigator
import app.sotreus.core.navigation.WelcomeRoute

fun NavGraphBuilder.onboardingGraph(nav: SotreusNavigator) {
    composable<WelcomeRoute> {
        WelcomeScreen(onStarted = { nav.replace(NowRoute) }, onWhatCanSee = { nav.navigate(AboutRoute) })
    }
    composable<PermissionsRoute> { PermissionsScreen(onDone = nav::back) }
    composable<AboutRoute> { AboutScreen(onBack = nav::back) }
}
