package com.ezequiel.padelcounter.mobile

import android.content.Context
import android.content.Intent
import com.ezequiel.padelcounter.MatchConfig
import com.ezequiel.padelcounter.MatchState
import com.ezequiel.padelcounter.GoalEvent
import com.ezequiel.padelcounter.Sport
import com.google.android.gms.wearable.*
import org.json.JSONArray
import org.json.JSONObject

const val SYNC_PATH = "/padel/snapshot"
const val ACTION_SYNCED = "com.ezequiel.padelcounter.mobile.SYNCED"

data class PhoneMatch(val config: MatchConfig, val state: MatchState)
data class PhoneRecord(val endedAt: Long, val match: PhoneMatch, val manuallyEntered: Boolean = false)

object PhoneCodec {
    fun matchToJson(match: PhoneMatch) = JSONObject().apply {
        val c = match.config; val s = match.state
        put("a", c.teamA); put("b", c.teamB); put("max", c.maxSets); put("adv", c.advantage)
        put("colorA", c.colorA); put("colorB", c.colorB); put("doubles", c.doubles); put("serverA", c.initialServerA)
        put("sport", c.sport.name)
        put("userPlayer", c.userPlayer); put("teamAPlayers", JSONArray(c.teamAPlayers)); put("teamBPlayers", JSONArray(c.teamBPlayers))
        put("pa", s.pointsA); put("pb", s.pointsB); put("ga", s.gamesA); put("gb", s.gamesB)
        put("sa", s.setsA); put("sb", s.setsB); put("done", s.finished); put("completedGames", s.completedGames)
        put("startedAt", s.startedAt); put("setStartedAt", s.setStartedAt); put("gameStartedAt", s.gameStartedAt)
        put("setDurations", JSONArray(s.setDurations)); put("gameDurations", JSONArray(s.gameDurations)); put("setScores", JSONArray(s.setScores))
        put("goalEvents", JSONArray().apply { s.goalEvents.forEach { put(JSONObject().put("teamA", it.teamA).put("elapsed", it.elapsedMillis)) } })
        put("hrAvg", s.averageHeartRate); put("hrMax", s.maxHeartRate); put("distance", s.distanceMeters); put("calories", s.calories); put("steps", s.steps); put("distanceEstimated", s.distanceEstimated)
    }

    fun matchFromJson(json: JSONObject) = PhoneMatch(
        MatchConfig(json.getString("a"), json.getString("b"), json.getInt("max"), json.getBoolean("adv"), json.optInt("colorA", 0), json.optInt("colorB", 1), json.optBoolean("doubles", true), json.optBoolean("serverA", true), runCatching { Sport.valueOf(json.optString("sport", Sport.PADEL.name)) }.getOrDefault(Sport.PADEL), json.optString("userPlayer", "Yo"), json.optJSONArray("teamAPlayers").strings(), json.optJSONArray("teamBPlayers").strings()),
        MatchState(
            pointsA = json.getInt("pa"), pointsB = json.getInt("pb"), gamesA = json.getInt("ga"), gamesB = json.getInt("gb"),
            setsA = json.getInt("sa"), setsB = json.getInt("sb"), finished = json.getBoolean("done"), completedGames = json.optInt("completedGames", 0),
            startedAt = json.optLong("startedAt", System.currentTimeMillis()), setStartedAt = json.optLong("setStartedAt", System.currentTimeMillis()),
            gameStartedAt = json.optLong("gameStartedAt", System.currentTimeMillis()), setDurations = json.optJSONArray("setDurations").longs(),
            gameDurations = json.optJSONArray("gameDurations").longs(), setScores = json.optJSONArray("setScores").strings(),
            averageHeartRate = json.optDouble("hrAvg", 0.0), maxHeartRate = json.optDouble("hrMax", 0.0),
            distanceMeters = json.optDouble("distance", 0.0), calories = json.optDouble("calories", 0.0), steps = json.optLong("steps", 0), distanceEstimated = json.optBoolean("distanceEstimated", false),
            goalEvents = json.optJSONArray("goalEvents").goals()
        )
    )

    fun historyFromJson(raw: String): List<PhoneRecord> {
        val array = JSONArray(raw)
        return (0 until array.length()).map { i -> array.getJSONObject(i).let { PhoneRecord(it.getLong("endedAt"), matchFromJson(it), it.optBoolean("manual", false)) } }
    }

    fun historyToJson(records: List<PhoneRecord>) = JSONArray().apply {
        records.take(50).forEach { put(matchToJson(it.match).put("endedAt", it.endedAt).put("manual", it.manuallyEntered)) }
    }.toString()

    private fun JSONArray?.longs() = if (this == null) emptyList() else (0 until length()).map { getLong(it) }
    private fun JSONArray?.strings() = if (this == null) emptyList() else (0 until length()).map { getString(it) }
    private fun JSONArray?.goals() = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it).let { goal -> GoalEvent(goal.optBoolean("teamA"), goal.optLong("elapsed")) } }
}

object MobileSync {
    fun publish(context: Context) {
        val prefs = context.getSharedPreferences("match", Context.MODE_PRIVATE)
        val updatedAt = maxOf(System.currentTimeMillis(), prefs.getLong("sync_updated", 0L) + 1L)
        prefs.edit().putLong("sync_updated", updatedAt).apply()
        val request = PutDataMapRequest.create(SYNC_PATH).apply {
            dataMap.putString("current", prefs.getString("current", null) ?: "")
            dataMap.putString("history", prefs.getString("history", "[]") ?: "[]")
            dataMap.putLong("updatedAt", updatedAt)
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(request)
    }
}

class MobileDataListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED || event.dataItem.uri.path != SYNC_PATH) return@forEach
            val map = DataMapItem.fromDataItem(event.dataItem).dataMap
            val prefs = getSharedPreferences("match", MODE_PRIVATE)
            val remoteUpdated = map.getLong("updatedAt")
            val sourceNode = event.dataItem.uri.host.orEmpty().ifBlank { "remote" }
            val receivedKey = "sync_received_$sourceNode"
            if (remoteUpdated <= prefs.getLong(receivedKey, 0L)) return@forEach
            val remoteCurrent = map.getString("current") ?: ""
            val remoteHistory = map.getString("history") ?: "[]"
            val previousCurrent = prefs.getString("current", null)
            val previousHistory = prefs.getString("history", "[]") ?: "[]"
            val dataChanged = remoteCurrent != previousCurrent.orEmpty() || remoteHistory != previousHistory
            val matchJustStarted = previousCurrent.isNullOrBlank() && remoteCurrent.isNotBlank()
            val matchJustEnded = !previousCurrent.isNullOrBlank() && remoteCurrent.isBlank()
            val newFinishedRecord = latestEndedAt(remoteHistory) != null && latestEndedAt(remoteHistory) != latestEndedAt(previousHistory)
            prefs.edit().apply {
                if (remoteCurrent.isBlank()) remove("current") else putString("current", remoteCurrent)
                putString("history", remoteHistory)
                if (matchJustEnded || newFinishedRecord) putBoolean("open_latest_detail", true)
                putLong(receivedKey, remoteUpdated)
            }.apply()
            if (dataChanged) {
                sendBroadcast(Intent(ACTION_SYNCED).setPackage(packageName))
            }
        }
    }

    private fun latestEndedAt(raw: String): Long? = runCatching {
        JSONArray(raw).takeIf { it.length() > 0 }?.getJSONObject(0)?.optLong("endedAt")
    }.getOrNull()
}
