package app.sotreus.feature.attention

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.sotreus.core.navigation.AttentionRoute
import app.sotreus.core.navigation.SotreusNavigator

fun NavGraphBuilder.attentionGraph(nav: SotreusNavigator) {
    composable<AttentionRoute> { AttentionScreen(navigate = nav::navigate, onBack = nav::back) }
}
