package app.sotreus.intelligence

import app.sotreus.intelligence.AutoPlaces.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPlacesTest {
    private val lat0 = 12.93276
    private val lon0 = 77.74468

    /** [m] metres north of the start point. */
    private fun north(m: Double) = lat0 + m / 111_195.0

    private fun fix(sec: Int, northM: Double = 0.0, acc: Float = 8f) = AutoPlaces.Fix(sec * 1_000L, north(northM), lon0, acc)

    private fun run(fixes: List<AutoPlaces.Fix>, areas: MutableList<AutoPlaces.Area> = mutableListOf(), radius: Int = 50): Pair<AutoPlaces.State, List<Action>> {
        var state = AutoPlaces.State()
        val actions = ArrayList<Action>()
        var nextId = 100L
        for (f in fixes) {
            val (s, a) = AutoPlaces.step(state, areas, f, radius)
            state = s
            if (a != Action.Keep) actions += a
            if (a is Action.Create) {
                val id = nextId++
                areas += AutoPlaces.Area(id, a.lat, a.lon, a.radiusM, auto = true)
                state = AutoPlaces.created(state, id)
            }
        }
        return state to actions
    }

    @Test fun threeSettledMinutesMakeOnePlace() {
        val (state, actions) = run((0..180 step 10).map { fix(it, northM = (it % 3).toDouble()) })
        assertEquals(1, actions.count { it is Action.Create })
        assertEquals(100L, state.placeId)
        val create = actions.single() as Action.Create
        assertTrue(PlaceGeofence.distanceM(create.lat, create.lon, lat0, lon0) < 3)
    }

    @Test fun movingNeverMakesAPlace() {
        // Walking 1.5 m/s for 10 minutes: never 3 minutes within 50 m of a start point.
        val (state, actions) = run((0..600 step 10).map { fix(it, northM = it * 1.5) })
        assertTrue(actions.isEmpty())
        assertNull(state.placeId)
    }

    @Test fun coarseFixesChangeNothing() {
        val (_, actions) = run((0..300 step 10).map { fix(it, acc = 40f) })
        assertTrue(actions.isEmpty())
    }

    @Test fun oneJumpDoesNotLeaveThePlace() {
        val areas = mutableListOf(AutoPlaces.Area(1, lat0, lon0, 50, auto = true))
        val fixes = listOf(fix(0), fix(10, 300.0), fix(20), fix(30, 5.0))
        val (state, actions) = run(fixes, areas)
        assertEquals(listOf<Action>(Action.Enter(1)), actions)
        assertEquals(1L, state.placeId)
    }

    @Test fun leavingNeedsTwoFixesTwentySecondsApart() {
        val areas = mutableListOf(AutoPlaces.Area(1, lat0, lon0, 50, auto = true))
        val fixes = listOf(fix(0), fix(10, 200.0), fix(20, 210.0), fix(31, 220.0))
        val (state, actions) = run(fixes, areas)
        assertEquals(listOf(Action.Enter(1), Action.Leave), actions)
        assertNull(state.placeId)
    }

    @Test fun smallDriftInsideTheExitMarginStays() {
        val areas = mutableListOf(AutoPlaces.Area(1, lat0, lon0, 50, auto = true))
        // Entered at the centre, then 90 m out: inside the 50 m radius plus the 50 m exit margin.
        val (state, actions) = run(listOf(fix(0)) + (10..120 step 10).map { fix(it, northM = 90.0) }, areas)
        assertEquals(listOf<Action>(Action.Enter(1)), actions)
        assertEquals(1L, state.placeId)
    }

    @Test fun aUserPlaceWinsOverAnAutomaticOne() {
        val areas = mutableListOf(
            AutoPlaces.Area(1, lat0, lon0, 50, auto = true),
            AutoPlaces.Area(2, north(20.0), lon0, 150, auto = false),
        )
        val (state, _) = run(listOf(fix(0)), areas)
        assertEquals(2L, state.placeId)
    }

    @Test fun settlingAfterLeavingMakesASecondPlace() {
        val first = (0..180 step 10).map { fix(it) }
        val travel = (190..400 step 10).map { fix(it, northM = (it - 180) * 2.0) }
        val second = (410..600 step 10).map { fix(it, northM = 440.0) }
        val (state, actions) = run(first + travel + second)
        assertEquals(2, actions.count { it is Action.Create })
        assertTrue(Action.Leave in actions)
        assertEquals(101L, state.placeId)
    }

    @Test fun theCentreLeansToTheBetterFix() {
        val c = AutoPlaces.centre(listOf(AutoPlaces.Fix(0, north(0.0), lon0, 5f), AutoPlaces.Fix(1, north(20.0), lon0, 20f)))
        assertTrue(PlaceGeofence.distanceM(c.lat, c.lon, lat0, lon0) < 3)
        assertEquals(5f, c.accuracyM)
    }
}
