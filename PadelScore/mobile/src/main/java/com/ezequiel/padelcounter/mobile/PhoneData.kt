package com.ezequiel.padelcounter.mobile

import android.content.Context
import android.content.Intent
import com.ezequiel.padelcounter.MatchConfig
import com.ezequiel.padelcounter.MatchState
import com.google.android.gms.wearable.*
import org.json.JSONArray
import org.json.JSONObject

const val SYNC_PATH = "/padel/snapshot"
const val ACTION_SYNCED = "com.ezequiel.padelcounter.mobile.SYNCED"

data class PhoneMatch(val config: MatchConfig, val state: MatchState)
data class PhoneRecord(val endedAt: Long, val match: PhoneMatch)

object PhoneCodec {
    fun matchToJson(match: PhoneMatch) = JSONObject().apply {
        val c = match.config; val s = match.state
        put("a", c.teamA); put("b", c.teamB); put("max", c.maxSets); put("adv", c.advantage)
        put("colorA", c.colorA); put("colorB", c.colorB); put("doubles", c.doubles); put("serverA", c.initialServerA)
        put("pa", s.pointsA); put("pb", s.pointsB); put("ga", s.gamesA); put("gb", s.gamesB)
        put("sa", s.setsA); put("sb", s.setsB); put("done", s.finished); put("completedGames", s.completedGames)
        put("startedAt", s.startedAt); put("setStartedAt", s.setStartedAt); put("gameStartedAt", s.gameStartedAt)
        put("setDurations", JSONArray(s.setDurations)); put("gameDurations", JSONArray(s.gameDurations)); put("setScores", JSONArray(s.setScores))
    }

    fun matchFromJson(json: JSONObject) = PhoneMatch(
        MatchConfig(json.getString("a"), json.getString("b"), json.getInt("max"), json.getBoolean("adv"), json.optInt("colorA", 0), json.optInt("colorB", 1), json.optBoolean("doubles", true), json.optBoolean("serverA", true)),
        MatchState(
            pointsA = json.getInt("pa"), pointsB = json.getInt("pb"), gamesA = json.getInt("ga"), gamesB = json.getInt("gb"),
            setsA = json.getInt("sa"), setsB = json.getInt("sb"), finished = json.getBoolean("done"), completedGames = json.optInt("completedGames", 0),
            startedAt = json.optLong("startedAt", System.currentTimeMillis()), setStartedAt = json.optLong("setStartedAt", System.currentTimeMillis()),
            gameStartedAt = json.optLong("gameStartedAt", System.currentTimeMillis()), setDurations = json.optJSONArray("setDurations").longs(),
            gameDurations = json.optJSONArray("gameDurations").longs(), setScores = json.optJSONArray("setScores").strings()
        )
    )

    fun historyFromJson(raw: String): List<PhoneRecord> {
        val array = JSONArray(raw)
        return (0 until array.length()).map { i -> array.getJSONObject(i).let { PhoneRecord(it.getLong("endedAt"), matchFromJson(it)) } }
    }

    fun historyToJson(records: List<PhoneRecord>) = JSONArray().apply {
        records.take(50).forEach { put(matchToJson(it.match).put("endedAt", it.endedAt)) }
    }.toString()

    private fun JSONArray?.longs() = if (this == null) emptyList() else (0 until length()).map { getLong(it) }
    private fun JSONArray?.strings() = if (this == null) emptyList() else (0 until length()).map { getString(it) }
}

object MobileSync {
    fun publish(context: Context) {
        val prefs = context.getSharedPreferences("match", Context.MODE_PRIVATE)
        val updatedAt = System.currentTimeMillis()
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
            if (remoteUpdated <= prefs.getLong("sync_updated", 0L)) return@forEach
            val remoteCurrent = map.getString("current") ?: ""
            val remoteHistory = map.getString("history") ?: "[]"
            val matchJustEnded = !prefs.getString("current", null).isNullOrBlank() && remoteCurrent.isBlank()
            prefs.edit().apply {
                if (remoteCurrent.isBlank()) remove("current") else putString("current", remoteCurrent)
                putString("history", remoteHistory)
                if (matchJustEnded) putBoolean("open_latest_detail", true)
                putLong("sync_updated", remoteUpdated)
            }.apply()
            sendBroadcast(Intent(ACTION_SYNCED).setPackage(packageName))
        }
    }
}
