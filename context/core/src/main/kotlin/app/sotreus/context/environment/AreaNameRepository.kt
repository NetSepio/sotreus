package app.sotreus.context.environment

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** On-phone lookups from the bundled Natural Earth boundaries: area names and warning regions. Nothing is sent. */
@Singleton
class AreaNameRepository @Inject constructor(@ApplicationContext private val app: Context) {
    private val names by lazy { AreaNames(asset("countries.bin"), asset("places.bin")) }
    val regions by lazy { RegionLookup(asset("warning_regions.bin")) }

    private fun asset(name: String) = app.assets.open("regions/$name").use { it.readBytes() }

    suspend fun areaAt(lat: Double, lon: Double): AreaName? =
        withContext(Dispatchers.Default) { runCatching { names.at(lat, lon) }.getOrNull() }

    suspend fun regionCodeAt(lat: Double, lon: Double): String? =
        withContext(Dispatchers.Default) { runCatching { regions.codeAt(lat, lon) }.getOrNull() }

    suspend fun warningRegionAt(lat: Double, lon: Double): WarningRegion? =
        withContext(Dispatchers.Default) { runCatching { regions.warningRegion(lat, lon) }.getOrNull() }
}
