package app.sotreus.feature.edge

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.sotreus.core.navigation.EdgeMapRoute
import app.sotreus.core.navigation.EdgeRoute
import app.sotreus.core.navigation.SotreusNavigator

fun NavGraphBuilder.edgeGraph(nav: SotreusNavigator) {
    composable<EdgeRoute> { EdgeScreen(onBack = nav::back, onPickOnMap = { nav.navigate(EdgeMapRoute) }) }
    composable<EdgeMapRoute> { EdgeMapPickerScreen(onBack = nav::back) }
}
