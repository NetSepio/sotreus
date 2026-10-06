package app.sotreus.feature.context

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.sotreus.core.navigation.AircraftRoute
import app.sotreus.core.navigation.ContextRoute
import app.sotreus.core.navigation.ContextSourcesRoute
import app.sotreus.core.navigation.RemoteIdRoute
import app.sotreus.core.navigation.SatelliteRoute
import app.sotreus.core.navigation.SotreusNavigator

fun NavGraphBuilder.contextGraph(nav: SotreusNavigator) {
    composable<ContextRoute> { ContextScreen(navigate = nav::navigate) }
    composable<AircraftRoute> { AircraftScreen(onBack = nav::back) }
    composable<SatelliteRoute> { SatelliteScreen(onBack = nav::back) }
    composable<RemoteIdRoute> { RemoteIdScreen(onBack = nav::back) }
    composable<ContextSourcesRoute> { ContextSourcesScreen(onBack = nav::back) }
}
