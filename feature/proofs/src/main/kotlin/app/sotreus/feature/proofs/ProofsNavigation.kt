package app.sotreus.feature.proofs

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.sotreus.core.navigation.ProofsRoute
import app.sotreus.core.navigation.ReceiptRoute
import app.sotreus.core.navigation.SotreusNavigator
import app.sotreus.core.navigation.StampRoute
import app.sotreus.core.navigation.WalletRoute

/** Solana Mobile screens (S2–S5). Reachable only when the device is a Solana Mobile device. */
fun NavGraphBuilder.proofsGraph(nav: SotreusNavigator) {
    composable<WalletRoute> { WalletScreen(onLinked = nav::back, onBack = nav::back) }
    composable<ProofsRoute> { ProofsScreen(navigate = nav::navigate, onBack = nav::back) }
    composable<StampRoute> { StampScreen(navigate = nav::navigate, replace = nav::replace, onBack = nav::back) }
    composable<ReceiptRoute> { ReceiptScreen(navigate = nav::navigate, onClose = nav::back) }
}
