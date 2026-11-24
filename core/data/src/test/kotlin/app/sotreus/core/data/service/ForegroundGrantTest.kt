package app.sotreus.core.data.service

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundGrantTest {
    private val radios = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    private val both = radios or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION

    private fun run(sdk: Int, location: Boolean, allowed: Set<Int>): Pair<ForegroundGrant, List<Int>> {
        val asked = mutableListOf<Int>()
        val grant = ForegroundGrant.request(sdk, location) { asked += it; it in allowed }
        return grant to asked
    }

    @Test fun locationIsAskedForOnlyWhenUsed() {
        assertEquals(ForegroundGrant.RADIOS to listOf(radios), run(36, location = false, allowed = setOf(radios, both)))
        assertEquals(ForegroundGrant.WITH_LOCATION to listOf(both), run(36, location = true, allowed = setOf(radios, both)))
    }

    @Test fun aRefusedLocationTypeKeepsTheServiceUpWithoutIt() {
        assertEquals(ForegroundGrant.RADIOS to listOf(both, radios), run(36, location = true, allowed = setOf(radios)))
    }

    @Test fun everyTypeRefusedIsReported() {
        assertEquals(ForegroundGrant.REFUSED to listOf(both, radios), run(34, location = true, allowed = emptySet()))
        assertEquals(ForegroundGrant.REFUSED, run(28, location = true, allowed = emptySet()).first)
    }

    @Test fun androidNineHasNoTypes() {
        assertEquals(ForegroundGrant.WITH_LOCATION to listOf(0), run(28, location = true, allowed = setOf(0)))
        assertEquals(ForegroundGrant.RADIOS to listOf(0), run(28, location = false, allowed = setOf(0)))
    }
}
