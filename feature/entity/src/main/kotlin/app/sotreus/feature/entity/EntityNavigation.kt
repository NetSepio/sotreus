package app.sotreus.feature.entity

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.sotreus.core.navigation.EntityRoute
import app.sotreus.core.navigation.EvidenceRoute
import app.sotreus.core.navigation.ProximityRoute
import app.sotreus.core.navigation.SotreusNavigator

fun NavGraphBuilder.entityGraph(nav: SotreusNavigator) {
    composable<EntityRoute> { EntityScreen(navigate = nav::navigate, onBack = nav::back) }
    composable<ProximityRoute> { ProximityScreen(onBack = nav::back) }
    composable<EvidenceRoute> { EvidenceScreen(onBack = nav::back) }
}
