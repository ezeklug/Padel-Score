package com.ezequiel.padelcounter

import android.content.Context
import android.content.Intent
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

class PadelDataListenerService : WearableListenerService() {
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
