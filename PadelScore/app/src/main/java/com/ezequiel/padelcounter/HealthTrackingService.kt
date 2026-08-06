package com.ezequiel.padelcounter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.*
import androidx.health.services.client.ExerciseUpdateCallback
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.MoreExecutors

object HealthMetricsStore {
    private const val PREFS = "health_metrics"
    fun reset(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    fun record(context: Context, heartRates: List<Double>, distance: Double?, calories: Double?, steps: Long?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sum = prefs.getFloat("hrSum", 0f).toDouble() + heartRates.sum()
        val count = prefs.getInt("hrCount", 0) + heartRates.size
        val max = maxOf(prefs.getFloat("hrMax", 0f).toDouble(), heartRates.maxOrNull() ?: 0.0)
        prefs.edit().putFloat("hrSum", sum.toFloat()).putInt("hrCount", count).putFloat("hrMax", max.toFloat())
            .putFloat("distance", maxOf(prefs.getFloat("distance", 0f).toDouble(), distance ?: 0.0).toFloat())
            .putFloat("calories", maxOf(prefs.getFloat("calories", 0f).toDouble(), calories ?: 0.0).toFloat())
            .putLong("steps", maxOf(prefs.getLong("steps", 0), steps ?: 0)).apply()
    }
    fun apply(context: Context, state: MatchState): MatchState {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); val count = prefs.getInt("hrCount", 0)
        val measuredDistance = prefs.getFloat("distance", 0f).toDouble(); val steps = prefs.getLong("steps", 0)
        return state.copy(
            averageHeartRate = if (count == 0) 0.0 else (prefs.getFloat("hrSum", 0f) / count).toDouble(),
            maxHeartRate = prefs.getFloat("hrMax", 0f).toDouble(), distanceMeters = if (measuredDistance > 0) measuredDistance else steps * 0.75,
            calories = prefs.getFloat("calories", 0f).toDouble(), steps = steps, distanceEstimated = measuredDistance <= 0 && steps > 0
        )
    }
}

class HealthTrackingService : Service(), SensorEventListener {
    private val exerciseClient by lazy { HealthServices.getClient(this).exerciseClient }
    private val sensorManager by lazy { getSystemService(SensorManager::class.java) }
    private var stepCounterBaseline: Float? = null
    private var detectedSteps = 0L
    private val callback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            val metrics = update.latestMetrics
            val heartRates = metrics.getData(DataType.HEART_RATE_BPM).map { it.value }
            val distance = metrics.getData(DataType.DISTANCE_TOTAL)?.total
            val calories = metrics.getData(DataType.CALORIES_TOTAL)?.total
            val steps = metrics.getData(DataType.STEPS_TOTAL)?.total
            HealthMetricsStore.record(this@HealthTrackingService, heartRates, distance, calories, steps)
        }
        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) = Unit
        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) = Unit
        override fun onRegistered() = Unit
        override fun onRegistrationFailed(throwable: Throwable) {
            getSharedPreferences("health_metrics", MODE_PRIVATE).edit().putString("error", "callback: ${throwable.message}").apply()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("match_tracking", "Partido activo", NotificationManager.IMPORTANCE_LOW))
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(41, NotificationCompat.Builder(this, "match_tracking").setSmallIcon(com.ezequiel.padelcounter.R.drawable.ic_launcher).setContentTitle("Tanteo").setContentText("Registrando actividad del partido").setContentIntent(pending).setOngoing(true).build())
        startStepTracking()
        exerciseClient.setUpdateCallback(callback)
        startExercise()
    }

    private fun startStepTracking() {
        val counter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val detector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        when {
            counter != null -> sensorManager.registerListener(this, counter, SensorManager.SENSOR_DELAY_NORMAL)
            detector != null -> sensorManager.registerListener(this, detector, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)?.let { heartRate ->
            sensorManager.registerListener(this, heartRate, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_HEART_RATE) {
            val heartRate = event.values.firstOrNull()?.toDouble() ?: return
            if (heartRate > 0) HealthMetricsStore.record(this, listOf(heartRate), null, null, null)
            return
        }
        val steps = when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                val current = event.values.firstOrNull() ?: return
                val baseline = stepCounterBaseline ?: current.also { stepCounterBaseline = it }
                (current - baseline).coerceAtLeast(0f).toLong()
            }
            Sensor.TYPE_STEP_DETECTOR -> ++detectedSteps
            else -> return
        }
        HealthMetricsStore.record(this, emptyList(), null, null, steps)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun startExercise() {
        Futures.addCallback(exerciseClient.getCapabilitiesAsync(), object : FutureCallback<ExerciseCapabilities> {
            override fun onSuccess(capabilities: ExerciseCapabilities) {
                val type = when {
                    capabilities.supportedExerciseTypes.contains(ExerciseType.WALKING) -> ExerciseType.WALKING
                    capabilities.supportedExerciseTypes.contains(ExerciseType.RUNNING) -> ExerciseType.RUNNING
                    else -> ExerciseType.WORKOUT
                }
                val supported = capabilities.getExerciseTypeCapabilities(type).supportedDataTypes
                val requested = setOf(DataType.HEART_RATE_BPM, DataType.DISTANCE_TOTAL, DataType.CALORIES_TOTAL, DataType.STEPS_TOTAL).filterTo(mutableSetOf()) { it in supported }
                getSharedPreferences("health_metrics", MODE_PRIVATE).edit().putString("supported", supported.joinToString { it.name }).apply()
                val config = ExerciseConfig(type, requested, false, false)
                exerciseClient.startExerciseAsync(config)
            }
            override fun onFailure(t: Throwable) {
                getSharedPreferences("health_metrics", MODE_PRIVATE).edit().putString("error", "exercise: ${t.message}").apply()
            }
        }, MoreExecutors.directExecutor())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            exerciseClient.endExerciseAsync(); stopSelf(); return START_NOT_STICKY
        }
        return START_STICKY
    }
    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        exerciseClient.clearUpdateCallbackAsync(callback)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
