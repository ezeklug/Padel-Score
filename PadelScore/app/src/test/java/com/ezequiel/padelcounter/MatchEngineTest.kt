package com.ezequiel.padelcounter

import org.junit.Assert.*
import org.junit.Test

class MatchEngineTest {
    private val advantage = MatchConfig(maxSets = 3, advantage = true)

    @Test fun `team names capitalize first letter and words`() {
        assertEquals("Mi Equipo Rojo", formatTeamNameInput("mi equipo rojo"))
        assertEquals("EZE Team", formatTeamNameInput("eZE team"))
    }

    @Test fun `game needs two point margin with advantage`() {
        var state = MatchState(pointsA = 3, pointsB = 3)
        state = MatchEngine.point(state, true, advantage)
        assertEquals("AD", MatchEngine.pointLabel(state.pointsA, state.pointsB, false, true))
        state = MatchEngine.point(state, false, advantage)
        assertEquals(4, state.pointsA)
        assertEquals(4, state.pointsB)
        state = MatchEngine.point(state, true, advantage)
        assertEquals(5, state.pointsA)
        assertEquals(4, state.pointsB)
    }

    @Test fun `no-ad deuce ends on next point`() {
        val state = MatchEngine.point(MatchState(pointsA = 3, pointsB = 3), true, advantage.copy(advantage = false))
        assertEquals(1, state.gamesA)
        assertEquals(0, state.pointsA)
    }

    @Test fun `tie break at six all wins set seven six`() {
        var state = MatchState(gamesA = 6, gamesB = 6, pointsA = 6, pointsB = 5)
        state = MatchEngine.point(state, true, advantage)
        assertEquals(1, state.setsA)
        assertEquals(0, state.gamesA)
    }

    @Test fun `best of three finishes after two sets`() {
        val state = MatchEngine.point(MatchState(gamesA = 6, gamesB = 5, pointsA = 3, setsA = 1), true, advantage)
        assertTrue(state.finished)
        assertEquals(2, state.setsA)
    }

    @Test fun `doubles tie break rotates team and player in service order`() {
        val config = advantage.copy(initialServerA = true)
        val expected = listOf(
            true to 1,
            false to 1,
            false to 1,
            true to 2,
            true to 2,
            false to 2,
            false to 2,
            true to 1,
        )

        expected.forEachIndexed { played, (teamA, player) ->
            val state = MatchState(gamesA = 6, gamesB = 6, pointsA = played)
            assertEquals("team at point $played", teamA, MatchEngine.serverTeamA(state, config))
            assertEquals("player at point $played", player, MatchEngine.serverPlayer(state))
        }
    }
}
