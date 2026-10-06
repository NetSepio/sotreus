package app.sotreus.feature.friends

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.sotreus.core.navigation.FriendsRoute
import app.sotreus.core.navigation.QrShowRoute
import app.sotreus.core.navigation.SotreusNavigator

fun NavGraphBuilder.friendsGraph(nav: SotreusNavigator) {
    composable<FriendsRoute> { FriendsScreen(navigate = nav::navigate, onBack = nav::back) }
    composable<QrShowRoute> { QrShowScreen(onBack = nav::back) }
}
