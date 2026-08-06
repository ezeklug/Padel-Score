package com.ezequiel.padelcounter

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import org.json.JSONArray
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

const val SYNC_PATH = "/padel/snapshot"
const val ACTION_SYNCED = "com.ezequiel.padelcounter.SYNCED"

object DataSync {
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

class PadelDataListenerService : WearableListenerService() {
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
            var remoteHistory = map.getString("history") ?: "[]"
            val previousHistory = prefs.getString("history", "[]") ?: "[]"
            val previousCurrent = prefs.getString("current", null)
            val hadCurrent = !prefs.getString("current", null).isNullOrBlank()
            val matchJustStarted = !hadCurrent && remoteCurrent.isNotBlank()
            val matchJustEnded = hadCurrent && remoteCurrent.isBlank()
            val newFinishedRecord = latestEndedAt(remoteHistory) != null && latestEndedAt(remoteHistory) != latestEndedAt(previousHistory)
            if (matchJustEnded) {
                val metrics = HealthMetricsStore.apply(this, MatchState())
                remoteHistory = runCatching { JSONArray(remoteHistory).also { array -> if (array.length() > 0) array.getJSONObject(0).apply { put("hrAvg", metrics.averageHeartRate); put("hrMax", metrics.maxHeartRate); put("distance", metrics.distanceMeters); put("calories", metrics.calories); put("steps", metrics.steps); put("distanceEstimated", metrics.distanceEstimated) } }.toString() }.getOrDefault(remoteHistory)
            }
            val dataChanged = remoteCurrent != previousCurrent.orEmpty() || remoteHistory != previousHistory
            prefs.edit().apply {
                if (remoteCurrent.isBlank()) remove("current") else putString("current", remoteCurrent)
                putString("history", remoteHistory)
                if (matchJustEnded || newFinishedRecord) putBoolean("open_latest_detail", true)
                putLong(receivedKey, remoteUpdated)
            }.apply()
            if (matchJustStarted) {
                HealthMetricsStore.reset(this)
                ContextCompat.startForegroundService(this, Intent(this, HealthTrackingService::class.java))
            }
            if (matchJustEnded) startService(Intent(this, HealthTrackingService::class.java).setAction("STOP"))
            if (matchJustEnded) DataSync.publish(this)
            if (dataChanged) {
                sendBroadcast(Intent(ACTION_SYNCED).setPackage(packageName))
            }
        }
    }

    private fun latestEndedAt(raw: String): Long? = runCatching {
        JSONArray(raw).takeIf { it.length() > 0 }?.getJSONObject(0)?.optLong("endedAt")
    }.getOrNull()
}
