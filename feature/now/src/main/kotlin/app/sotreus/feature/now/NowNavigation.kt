package app.sotreus.feature.now

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.sotreus.core.navigation.NowRoute
import app.sotreus.core.navigation.SotreusNavigator

fun NavGraphBuilder.nowGraph(nav: SotreusNavigator) {
    composable<NowRoute> { NowScreen(navigate = nav::navigate) }
}
