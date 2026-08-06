package com.ezequiel.padelcounter

data class MatchConfig(
    val teamA: String = "Mi Equipo",
    val teamB: String = "Rival",
    val maxSets: Int = 1,
    val advantage: Boolean = false,
    val colorA: Int = 2,
    val colorB: Int = 1,
    val doubles: Boolean = true,
    val initialServerA: Boolean = true,
)

data class MatchState(
    val pointsA: Int = 0,
    val pointsB: Int = 0,
    val gamesA: Int = 0,
    val gamesB: Int = 0,
    val setsA: Int = 0,
    val setsB: Int = 0,
    val finished: Boolean = false,
    val completedGames: Int = 0,
    val startedAt: Long = System.currentTimeMillis(),
    val setStartedAt: Long = startedAt,
    val gameStartedAt: Long = startedAt,
    val setDurations: List<Long> = emptyList(),
    val gameDurations: List<Long> = emptyList(),
) {
    val tieBreak: Boolean get() = gamesA == 6 && gamesB == 6
}

object MatchEngine {
    fun point(state: MatchState, teamA: Boolean, config: MatchConfig, now: Long = System.currentTimeMillis()): MatchState {
        if (state.finished) return state
        val next = if (teamA) state.copy(pointsA = state.pointsA + 1)
        else state.copy(pointsB = state.pointsB + 1)
        val wonGame = if (next.tieBreak) {
            maxOf(next.pointsA, next.pointsB) >= 7 && kotlin.math.abs(next.pointsA - next.pointsB) >= 2
        } else if (config.advantage) {
            maxOf(next.pointsA, next.pointsB) >= 4 && kotlin.math.abs(next.pointsA - next.pointsB) >= 2
        } else {
            maxOf(next.pointsA, next.pointsB) >= 4
        }
        return if (wonGame) winGame(next, next.pointsA > next.pointsB, config, now) else next
    }

    private fun winGame(state: MatchState, teamA: Boolean, config: MatchConfig, now: Long): MatchState {
        val next = state.copy(
            pointsA = 0,
            pointsB = 0,
            gamesA = state.gamesA + if (teamA) 1 else 0,
            gamesB = state.gamesB + if (teamA) 0 else 1,
            completedGames = state.completedGames + 1,
            gameStartedAt = now,
            gameDurations = state.gameDurations + (now - state.gameStartedAt).coerceAtLeast(0),
        )
        val wonSet = maxOf(next.gamesA, next.gamesB) >= 6 &&
            (kotlin.math.abs(next.gamesA - next.gamesB) >= 2 || maxOf(next.gamesA, next.gamesB) == 7)
        if (!wonSet) return next
        val withSet = next.copy(
            gamesA = 0,
            gamesB = 0,
            setsA = next.setsA + if (teamA) 1 else 0,
            setsB = next.setsB + if (teamA) 0 else 1,
            setStartedAt = now,
            setDurations = next.setDurations + (now - next.setStartedAt).coerceAtLeast(0),
        )
        val setsNeeded = config.maxSets / 2 + 1
        return withSet.copy(finished = maxOf(withSet.setsA, withSet.setsB) >= setsNeeded)
    }

    fun pointLabel(own: Int, other: Int, tieBreak: Boolean, advantage: Boolean): String {
        if (tieBreak) return own.toString()
        if (advantage && own >= 3 && other >= 3) return when {
            own == other -> "40"
            own > other -> "AD"
            else -> "40"
        }
        return listOf("0", "15", "30", "40").getOrElse(own) { "40" }
    }

    fun serverTeamA(state: MatchState, config: MatchConfig): Boolean {
        val base = if (state.completedGames % 2 == 0) config.initialServerA else !config.initialServerA
        if (!state.tieBreak) return base
        val played = state.pointsA + state.pointsB
        return if (played == 0) base else if (((played - 1) / 2) % 2 == 0) !base else base
    }

    fun serverPlayer(state: MatchState): Int {
        if (!state.tieBreak) return (state.completedGames / 2) % 2 + 1
        val played = state.pointsA + state.pointsB
        val serviceTurn = if (played == 0) 0 else (played + 1) / 2
        return ((state.completedGames + serviceTurn) / 2) % 2 + 1
    }
}
