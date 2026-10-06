package app.sotreus.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.sotreus.R
import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.designsystem.icon.SotreusIcons
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.model.SotreusSettings
import app.sotreus.core.navigation.ContextRoute
import app.sotreus.core.navigation.FriendsRoute
import app.sotreus.core.navigation.HistoryRoute
import app.sotreus.core.navigation.JourneyRoute
import app.sotreus.core.navigation.NowRoute
import app.sotreus.core.navigation.SettingsRoute
import app.sotreus.core.navigation.SotreusNavigator
import app.sotreus.core.navigation.WelcomeRoute
import app.sotreus.core.ui.SotreusBottomNav
import app.sotreus.core.ui.SotreusNavItem
import app.sotreus.feature.attention.attentionGraph
import app.sotreus.feature.context.contextGraph
import app.sotreus.feature.entity.entityGraph
import app.sotreus.feature.friends.friendsGraph
import app.sotreus.feature.history.historyGraph
import app.sotreus.feature.journey.journeyGraph
import app.sotreus.feature.now.nowGraph
import app.sotreus.feature.onboarding.onboardingGraph
import app.sotreus.feature.place.placeGraph
import app.sotreus.feature.proofs.proofsGraph
import app.sotreus.feature.settings.settingsGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.reflect.KClass

/** Bottom-nav tabs. */
enum class TopLevelDestination(val route: Any, val routeClass: KClass<*>, val icon: ImageVector, @StringRes val label: Int) {
    NOW(NowRoute, NowRoute::class, SotreusIcons.Now, R.string.nav_now),
    HISTORY(HistoryRoute, HistoryRoute::class, SotreusIcons.History, R.string.nav_history),
    JOURNEY(JourneyRoute, JourneyRoute::class, SotreusIcons.Journey, R.string.nav_journey),
    CONTEXT(ContextRoute, ContextRoute::class, SotreusIcons.Context, R.string.nav_context),
    SETTINGS(SettingsRoute, SettingsRoute::class, SotreusIcons.Settings, R.string.nav_settings),
}

private class AppNavigator(private val nav: NavHostController) : SotreusNavigator {
    override fun navigate(route: Any) = nav.navigate(route) { launchSingleTop = true }

    override fun replace(route: Any) {
        val current = nav.currentDestination?.id
        nav.navigate(route) {
            launchSingleTop = true
            if (current != null) popUpTo(current) { inclusive = true }
        }
    }

    override fun back() {
        if (!nav.popBackStack()) nav.navigate(NowRoute)
    }

    override fun tab(route: Any) = nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** App shell: ink background, system insets, bottom nav on tab roots only. */
@Composable
fun SotreusApp(settings: SettingsRepository, pendingInvite: MutableStateFlow<String?>, navController: NavHostController = rememberNavController()) {
    val loaded by settings.settings.collectAsState(initial = null as SotreusSettings?)
    val current = loaded ?: run {
        Box(Modifier.fillMaxSize())
        return
    }
    val navigator = remember(navController) { AppNavigator(navController) }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val onTabRoot = TopLevelDestination.entries.any { destination.isOn(it) }
    val invite by pendingInvite.collectAsState()
    LaunchedEffect(invite) { if (invite != null && current.onboardingDone) navigator.navigate(FriendsRoute) }

    Scaffold(
        containerColor = SotreusTheme.colors.ink,
        contentColor = SotreusTheme.colors.text,
        bottomBar = {
            if (onTabRoot) {
                SotreusBottomNav(
                    TopLevelDestination.entries.map { tab ->
                        SotreusNavItem(
                            label = stringResource(tab.label),
                            icon = tab.icon,
                            selected = destination.isOn(tab),
                            onClick = { navigator.tab(tab.route) },
                        )
                    },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = if (current.onboardingDone) NowRoute else WelcomeRoute,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            onboardingGraph(navigator)
            nowGraph(navigator)
            historyGraph(navigator)
            journeyGraph(navigator)
            settingsGraph(navigator)
            entityGraph(navigator)
            attentionGraph(navigator)
            placeGraph(navigator)
            friendsGraph(navigator)
            proofsGraph(navigator)
            contextGraph(navigator)
        }
    }
}

private fun NavDestination?.isOn(tab: TopLevelDestination): Boolean =
    this?.hierarchy?.any { it.hasRoute(tab.routeClass) } == true
