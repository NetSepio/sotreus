package app.sotreus.core.navigation

/** What feature screens may ask of navigation. Implemented by the app around its NavController. */
interface SotreusNavigator {
    fun navigate(route: Any)

    /** Navigate and drop the current screen from the back stack (e.g. live session → summary). */
    fun replace(route: Any)

    fun back()

    /** Switch to a bottom-nav tab root. */
    fun tab(route: Any)
}
