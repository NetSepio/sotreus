package app.sotreus.feature.history

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.sotreus.core.navigation.HistoryRoute
import app.sotreus.core.navigation.SotreusNavigator

fun NavGraphBuilder.historyGraph(nav: SotreusNavigator) {
    composable<HistoryRoute> { HistoryScreen(navigate = nav::navigate) }
}
