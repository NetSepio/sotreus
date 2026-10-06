package app.sotreus.feature.journey

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.sotreus.core.navigation.CompareRoute
import app.sotreus.core.navigation.JourneyRoute
import app.sotreus.core.navigation.SessionLiveRoute
import app.sotreus.core.navigation.SessionRoute
import app.sotreus.core.navigation.SotreusNavigator

fun NavGraphBuilder.journeyGraph(nav: SotreusNavigator) {
    composable<JourneyRoute> { SessionsScreen(navigate = nav::navigate) }
    composable<SessionLiveRoute> { LiveSessionScreen(replace = nav::replace) }
    composable<SessionRoute> { SessionSummaryScreen(navigate = nav::navigate, onBack = nav::back) }
    composable<CompareRoute> { CompareScreen(navigate = nav::navigate, onBack = nav::back) }
}
