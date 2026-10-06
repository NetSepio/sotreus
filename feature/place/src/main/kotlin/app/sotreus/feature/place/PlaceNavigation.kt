package app.sotreus.feature.place

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.sotreus.core.navigation.PlaceRoute
import app.sotreus.core.navigation.PlacesRoute
import app.sotreus.core.navigation.SotreusNavigator

fun NavGraphBuilder.placeGraph(nav: SotreusNavigator) {
    composable<PlaceRoute> { PlaceScreen(navigate = nav::navigate, onBack = nav::back) }
    composable<PlacesRoute> { PlacesScreen(navigate = nav::navigate, onBack = nav::back) }
}
