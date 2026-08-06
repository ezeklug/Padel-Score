package com.ezequiel.padelcounter

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import org.json.JSONObject
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("match", MODE_PRIVATE) }
    private val syncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        migratePalette()
        val healthPermission = if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS
        val missingPermissions = listOf(healthPermission, Manifest.permission.ACTIVITY_RECOGNITION).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missingPermissions.isNotEmpty()) ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 72)
        val openLatest = prefs.getBoolean("open_latest_detail", false)
        prefs.edit().remove("open_latest_detail").apply()
        setContent { MaterialTheme { TanteoApp(loadSaved(), loadHistory(), openLatest, ::save, ::saveHistory) } }
        if (prefs.getLong("sync_updated", 0L) == 0L) DataSync.publish(this)
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(syncReceiver, IntentFilter(ACTION_SYNCED), RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        unregisterReceiver(syncReceiver)
        super.onStop()
    }

    private fun save(saved: SavedMatch?) {
        prefs.edit().apply {
            if (saved == null) remove("current") else putString("current", saved.toJson().toString())
        }.apply()
        DataSync.publish(this)
    }

    private fun loadSaved(): SavedMatch? = runCatching { savedFromJson(JSONObject(prefs.getString("current", null) ?: return null)) }.getOrNull()

    private fun loadHistory(): List<HistoryRecord> = runCatching {
        val array = JSONArray(prefs.getString("history", "[]"))
        (0 until array.length()).map { index ->
            val json = array.getJSONObject(index)
            HistoryRecord(json.getLong("endedAt"), savedFromJson(json), json.optBoolean("manual", false))
        }
    }.getOrDefault(emptyList())

    private fun saveHistory(records: List<HistoryRecord>) {
        val array = JSONArray()
        records.take(50).forEach { record -> array.put(record.saved.toJson().put("endedAt", record.endedAt).put("manual", record.manuallyEntered)) }
        prefs.edit().putString("history", array.toString()).apply()
        DataSync.publish(this)
    }

    private fun migratePalette() {
        if (prefs.getBoolean("fixed_colors_v3", false)) return
        fun migrate(json: JSONObject) { json.put("colorA", 0); json.put("colorB", 1) }
        prefs.edit().apply {
            prefs.getString("current", null)?.let { runCatching { JSONObject(it).also(::migrate).toString() }.getOrNull()?.let { value -> putString("current", value) } }
            prefs.getString("history", null)?.let { raw -> runCatching { JSONArray(raw).also { array -> (0 until array.length()).forEach { migrate(array.getJSONObject(it)) } }.toString() }.getOrNull()?.let { value -> putString("history", value) } }
            putBoolean("palette_v2", true)
            putBoolean("fixed_colors_v3", true)
        }.apply()
    }
}

private fun savedFromJson(json: JSONObject) = SavedMatch(
    MatchConfig(json.getString("a"), json.getString("b"), json.getInt("max"), json.getBoolean("adv"), json.optInt("colorA", 0), json.optInt("colorB", 1), json.optBoolean("doubles", true), json.optBoolean("serverA", true), runCatching { Sport.valueOf(json.optString("sport", Sport.PADEL.name)) }.getOrDefault(Sport.PADEL), json.optString("userPlayer", "Yo"), json.optJSONArray("teamAPlayers").toStringList(), json.optJSONArray("teamBPlayers").toStringList()),
    MatchState(
        pointsA = json.getInt("pa"), pointsB = json.getInt("pb"), gamesA = json.getInt("ga"), gamesB = json.getInt("gb"),
        setsA = json.getInt("sa"), setsB = json.getInt("sb"), finished = json.getBoolean("done"),
        completedGames = json.optInt("completedGames", 0), startedAt = json.optLong("startedAt", System.currentTimeMillis()),
        setStartedAt = json.optLong("setStartedAt", json.optLong("startedAt", System.currentTimeMillis())),
        gameStartedAt = json.optLong("gameStartedAt", json.optLong("startedAt", System.currentTimeMillis())),
        setDurations = json.optJSONArray("setDurations").toLongList(), gameDurations = json.optJSONArray("gameDurations").toLongList(),
        setScores = json.optJSONArray("setScores").toStringList(), averageHeartRate = json.optDouble("hrAvg", 0.0),
        maxHeartRate = json.optDouble("hrMax", 0.0), distanceMeters = json.optDouble("distance", 0.0), calories = json.optDouble("calories", 0.0),
        steps = json.optLong("steps", 0), distanceEstimated = json.optBoolean("distanceEstimated", false),
        goalEvents = json.optJSONArray("goalEvents").toGoalEvents()
    )
)

data class SavedMatch(val config: MatchConfig, val state: MatchState) {
    fun toJson() = JSONObject().apply {
        put("a", config.teamA); put("b", config.teamB); put("max", config.maxSets); put("adv", config.advantage)
        put("colorA", config.colorA); put("colorB", config.colorB)
        put("doubles", config.doubles); put("serverA", config.initialServerA)
        put("sport", config.sport.name)
        put("userPlayer", config.userPlayer); put("teamAPlayers", JSONArray(config.teamAPlayers)); put("teamBPlayers", JSONArray(config.teamBPlayers))
        put("pa", state.pointsA); put("pb", state.pointsB); put("ga", state.gamesA); put("gb", state.gamesB)
        put("sa", state.setsA); put("sb", state.setsB); put("done", state.finished)
        put("completedGames", state.completedGames)
        put("startedAt", state.startedAt); put("setStartedAt", state.setStartedAt); put("gameStartedAt", state.gameStartedAt)
        put("setDurations", JSONArray(state.setDurations)); put("gameDurations", JSONArray(state.gameDurations)); put("setScores", JSONArray(state.setScores))
        put("goalEvents", JSONArray().apply { state.goalEvents.forEach { put(JSONObject().put("teamA", it.teamA).put("elapsed", it.elapsedMillis)) } })
        put("hrAvg", state.averageHeartRate); put("hrMax", state.maxHeartRate); put("distance", state.distanceMeters); put("calories", state.calories); put("steps", state.steps); put("distanceEstimated", state.distanceEstimated)
    }
}

data class HistoryRecord(val endedAt: Long, val saved: SavedMatch, val manuallyEntered: Boolean = false)

private fun JSONArray?.toLongList(): List<Long> = if (this == null) emptyList() else (0 until length()).map { getLong(it) }
private fun JSONArray?.toStringList(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
private fun JSONArray?.toGoalEvents(): List<GoalEvent> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it).let { goal -> GoalEvent(goal.optBoolean("teamA"), goal.optLong("elapsed")) } }

private val Ink = Color(0xFF111820)
private val Panel = Color(0xFF1B2530)
private val Lime = Color(0xFF4EB3D3)
private val Muted = Color(0xFFA5B0BD)
private val Win = Color(0xFF35B779)
private val Draw = Color(0xFFF2B84B)
private val Loss = Color(0xFFE45C62)
private val TeamColors = listOf(Color(0xFFE6534E), Color(0xFF2878B5), Color(0xFF159A82), Color(0xFFE9A23B), Color(0xFF7959B8))

@Composable
private fun TanteoApp(initial: SavedMatch?, initialHistory: List<HistoryRecord>, openLatest: Boolean, persist: (SavedMatch?) -> Unit, persistHistory: (List<HistoryRecord>) -> Unit) {
    var screen by remember { mutableStateOf(if (openLatest && initialHistory.isNotEmpty()) "detail" else if (initial == null) "sport" else "score") }
    var config by remember { mutableStateOf(initial?.config ?: if (openLatest) initialHistory.firstOrNull()?.saved?.config ?: MatchConfig() else MatchConfig()) }
    var state by remember { mutableStateOf(initial?.state ?: MatchState()) }
    val history = remember { mutableStateListOf<MatchState>() }
    val records = remember { mutableStateListOf<HistoryRecord>().apply { addAll(initialHistory) } }
    var selectedRecord by remember { mutableStateOf<Int?>(if (openLatest && initialHistory.isNotEmpty()) 0 else null) }
    var locked by remember { mutableStateOf(false) }
    val view = LocalView.current
    val context = LocalContext.current

    Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
        when (screen) {
            "sport" -> SportSelectionScreen { sport -> config = normalizeWatchRoster(MatchConfig(sport = sport, colorA = 0, colorB = 1)); screen = "setup" }
            "setup" -> SetupScreen(config, { config = it }, { screen = "sport" }, { screen = "history" }, {
                val started = config.copy(
                    teamA = config.teamA.ifBlank { "Mi Equipo" },
                    teamB = config.teamB.ifBlank { "Rival" },
                    colorA = 0,
                    colorB = 1,
                )
                config = started
                state = MatchState()
                history.clear()
                persist(SavedMatch(started, state))
                HealthMetricsStore.reset(context)
                androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, HealthTrackingService::class.java))
                screen = "score"
            })
            "history" -> HistoryScreen(records, config.sport, { index -> selectedRecord = index; screen = "detail" }, { screen = "global" }, { screen = "setup" })
            "global" -> GlobalStatsWatch(records.filter { if (config.sport.isFootball) it.saved.config.sport.isFootball else it.saved.config.sport == config.sport }) { screen = "history" }
            "detail" -> selectedRecord?.let { index ->
                HistoryDetail(records[index],
                    onUpdate = { updated -> records[index] = updated; persistHistory(records.toList()) },
                    onDelete = { records.removeAt(index); persistHistory(records.toList()); selectedRecord = null; screen = "history" },
                    onBack = { screen = "history" })
            }
            "confirm" -> ConfirmScreen(
                onCancel = { screen = "score" },
                onConfirm = {
                    val finalState = HealthMetricsStore.apply(context, state).copy(finished = true)
                    records.add(0, HistoryRecord(System.currentTimeMillis(), SavedMatch(config, finalState)))
                    persistHistory(records.toList())
                    selectedRecord = 0
                    persist(null); config = MatchConfig(sport = config.sport); state = MatchState(); history.clear(); locked = false
                    context.startService(Intent(context, HealthTrackingService::class.java).setAction("STOP"))
                    screen = "detail"
                }
            )
            else -> ScoreScreen(config, state,
                onPoint = { teamA ->
                    history.add(state)
                    val previous = state
                    state = if (config.sport.isFootball) state.copy(
                        pointsA = state.pointsA + if (teamA) 1 else 0,
                        pointsB = state.pointsB + if (teamA) 0 else 1,
                        goalEvents = state.goalEvents + GoalEvent(
                            teamA,
                            (System.currentTimeMillis() - state.startedAt).coerceAtLeast(0)
                        )
                    ) else MatchEngine.point(state, teamA, config)
                    view.performHapticFeedback(if (state.setsA != previous.setsA || state.setsB != previous.setsB) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.CLOCK_TICK)
                    persist(SavedMatch(config, state))
                },
                onUndo = { if (history.isNotEmpty()) { state = history.removeAt(history.lastIndex); persist(SavedMatch(config, state)) } },
                onFinish = { screen = "confirm" }, locked = locked, onLockChange = { locked = it }
            )
        }
    }
}

@Composable
private fun SportSelectionScreen(select: (Sport) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("TANTEO", color = Lime, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text("ELEGIR DEPORTE", color = Muted, fontSize = 8.sp)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            SportChoiceWatch("PADEL", Sport.PADEL, Modifier.weight(1f), select)
            SportChoiceWatch("TENIS", Sport.TENNIS, Modifier.weight(1f), select)
            SportChoiceWatch("FUTBOL", Sport.FOOTBALL_5, Modifier.weight(1f), select)
        }
    }
}

@Composable
private fun SportChoiceWatch(label: String, sport: Sport, modifier: Modifier, select: (Sport) -> Unit) {
    Column(modifier.height(82.dp).clip(RoundedCornerShape(8.dp)).background(Panel).clickable { select(sport) }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
        Canvas(Modifier.size(38.dp)) {
            when (sport) {
                Sport.PADEL -> {
                    drawOval(Lime, topLeft = Offset(size.width * .12f, size.height * .05f), size = Size(size.width * .58f, size.height * .64f))
                    drawLine(Lime, Offset(size.width * .57f, size.height * .62f), Offset(size.width * .82f, size.height * .9f), strokeWidth = size.width * .14f)
                    listOf(.28f to .23f, .48f to .22f, .25f to .42f, .46f to .43f).forEach { (x, y) -> drawCircle(Ink, radius = size.width * .045f, center = Offset(size.width * x, size.height * y)) }
                    drawCircle(Color.White, radius = size.width * .075f, center = Offset(size.width * .82f, size.height * .18f))
                }
                Sport.TENNIS -> {
                    drawOval(Color.White, topLeft = Offset(size.width * .12f, size.height * .03f), size = Size(size.width * .6f, size.height * .65f), style = Stroke(size.width * .075f))
                    listOf(.26f, .4f, .54f).forEach { x -> drawLine(Muted, Offset(size.width * x, size.height * .1f), Offset(size.width * x, size.height * .59f), strokeWidth = 1.2f) }
                    listOf(.2f, .34f, .48f).forEach { y -> drawLine(Muted, Offset(size.width * .18f, size.height * y), Offset(size.width * .66f, size.height * y), strokeWidth = 1.2f) }
                    drawLine(Color.White, Offset(size.width * .58f, size.height * .61f), Offset(size.width * .82f, size.height * .91f), strokeWidth = size.width * .11f)
                    drawCircle(Lime, radius = size.width * .09f, center = Offset(size.width * .84f, size.height * .2f))
                }
                else -> {
                    drawCircle(Color.White, radius = size.minDimension * .41f, center = center)
                    val pentagon = Path()
                    repeat(5) { index ->
                        val angle = Math.toRadians((-90 + index * 72).toDouble())
                        val point = Offset(center.x + kotlin.math.cos(angle).toFloat() * size.width * .12f, center.y + kotlin.math.sin(angle).toFloat() * size.height * .12f)
                        if (index == 0) pentagon.moveTo(point.x, point.y) else pentagon.lineTo(point.x, point.y)
                    }
                    pentagon.close(); drawPath(pentagon, Ink)
                    repeat(5) { index ->
                        val angle = Math.toRadians((-90 + index * 72).toDouble())
                        val outer = Offset(center.x + kotlin.math.cos(angle).toFloat() * size.width * .34f, center.y + kotlin.math.sin(angle).toFloat() * size.height * .34f)
                        val inner = Offset(center.x + kotlin.math.cos(angle).toFloat() * size.width * .12f, center.y + kotlin.math.sin(angle).toFloat() * size.height * .12f)
                        drawLine(Ink, inner, outer, strokeWidth = size.width * .045f)
                        drawCircle(Ink, radius = size.width * .055f, center = outer)
                    }
                }
            }
        }
        Text(label, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun SetupScreen(config: MatchConfig, update: (MatchConfig) -> Unit, chooseSport: () -> Unit, history: () -> Unit, start: () -> Unit) {
    Column(
        Modifier.fillMaxSize().pointerInput(Unit) {
            var drag = 0f
            detectVerticalDragGestures(
                onDragStart = { drag = 0f },
                onVerticalDrag = { _, amount -> drag += amount },
                onDragEnd = { if (drag > 45f) history() }
            )
        }.padding(horizontal = 28.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text("<  ${config.sport.displayName.uppercase()}", color = Lime, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = chooseSport).padding(2.dp))
        if (config.sport.isFootball) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Choice("F5", config.sport == Sport.FOOTBALL_5, 42) { update(normalizeWatchRoster(config.copy(sport = Sport.FOOTBALL_5))) }
            Choice("F7", config.sport == Sport.FOOTBALL_7, 42) { update(normalizeWatchRoster(config.copy(sport = Sport.FOOTBALL_7))) }
            Choice("F11", config.sport == Sport.FOOTBALL_11, 42) { update(normalizeWatchRoster(config.copy(sport = Sport.FOOTBALL_11))) }
        }
        if (!config.sport.isFootball) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Sets", color = Muted, fontSize = 9.sp, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(1, 3, 5).forEach { n -> Choice(n.toString(), config.maxSets == n, width = 28) { update(config.copy(maxSets = n)) } }
                Choice("L", config.maxSets == 0, width = 36) { update(config.copy(maxSets = 0)) }
            }
        }
        if (!config.sport.isFootball) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Saque", color = Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Choice("A", config.initialServerA) { update(config.copy(initialServerA = true)) }
                Choice("B", !config.initialServerA) { update(config.copy(initialServerA = false)) }
            }
        }
        if (!config.sport.isFootball) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Ventaja", color = Muted, fontSize = 10.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Choice("SI", config.advantage) { update(config.copy(advantage = true)) }
                Choice("NO", !config.advantage) { update(config.copy(advantage = false)) }
            }
        }
        if (!config.sport.isFootball) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Modo", color = Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Choice("1v1", !config.doubles) { update(normalizeWatchRoster(config.copy(doubles = false))) }
                Choice("2v2", config.doubles) { update(normalizeWatchRoster(config.copy(doubles = true))) }
            }
        }
        Box(
            Modifier.fillMaxWidth(.82f).height(29.dp).clip(RoundedCornerShape(7.dp)).background(Lime).clickable(onClick = start),
            contentAlignment = Alignment.Center
        ) { Text("EMPEZAR", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun TeamsScreen(config: MatchConfig, update: (MatchConfig) -> Unit, back: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).pointerInput(Unit) {
            var drag = 0f
            detectVerticalDragGestures(
                onDragStart = { drag = 0f },
                onVerticalDrag = { _, amount -> drag += amount },
                onDragEnd = { if (drag > 45f) back() }
            )
        }.padding(horizontal = 27.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("INTEGRANTES", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(onClick = back).padding(3.dp))
        Spacer(Modifier.height(5.dp))
        Text("MI EQUIPO", color = TeamColors[0], fontSize = 9.sp, fontWeight = FontWeight.Bold)
        config.teamAPlayers.forEachIndexed { index, name ->
            Text(if (index == 0) "VOS" else "COMPAÑERO ${index + 1}", color = Muted, fontSize = 7.sp)
            NameField(name, TeamColors[0]) { value ->
                val list = config.teamAPlayers.toMutableList().apply { this[index] = value }
                update(config.copy(userPlayer = if (index == 0) value.ifBlank { "Yo" } else config.userPlayer, teamAPlayers = list))
            }
        }
        Spacer(Modifier.height(5.dp))
        Text("RIVALES", color = TeamColors[1], fontSize = 9.sp, fontWeight = FontWeight.Bold)
        config.teamBPlayers.forEachIndexed { index, name ->
            Text("RIVAL ${index + 1}", color = Muted, fontSize = 7.sp)
            NameField(name, TeamColors[1]) { value ->
                update(config.copy(teamBPlayers = config.teamBPlayers.toMutableList().apply { this[index] = value }))
            }
        }
        Spacer(Modifier.height(7.dp))
        Box(
            Modifier.fillMaxWidth(.72f).height(22.dp).clip(RoundedCornerShape(6.dp)).background(Lime).clickable(onClick = back),
            contentAlignment = Alignment.Center
        ) { Text("LISTO", color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
    }
}

private fun normalizeWatchRoster(config: MatchConfig): MatchConfig {
    val size = if (config.sport.isFootball) config.sport.teamSize else if (config.doubles) 2 else 1
    val user = config.userPlayer.ifBlank { "Yo" }
    val own = MutableList(size) { config.teamAPlayers.getOrNull(it).orEmpty() }
    val rivals = MutableList(size) { config.teamBPlayers.getOrNull(it).orEmpty() }
    own[0] = user
    return config.copy(teamA = "Mi Equipo", teamB = "Rival", userPlayer = user, teamAPlayers = own, teamBPlayers = rivals)
}

private fun watchMatchupLabel(config: MatchConfig): String {
    if (config.sport.isFootball) return "Mi Equipo vs Rival"
    val own = config.teamAPlayers.filter { it.isNotBlank() }.ifEmpty { listOf(config.userPlayer.ifBlank { "Yo" }) }
    val rivals = config.teamBPlayers.filter { it.isNotBlank() }.ifEmpty { listOf("Rival") }
    return "${own.joinToString(" ")} vs ${rivals.joinToString(" ")}"
}

@Composable
private fun HistoryScreen(records: List<HistoryRecord>, sport: Sport, open: (Int) -> Unit, global: () -> Unit, back: () -> Unit) {
    val filtered = records.withIndex().filter { if (sport.isFootball) it.value.saved.config.sport.isFootball else it.value.saved.config.sport == sport }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 25.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text("<  HISTORIAL", color = Lime, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(onClick = back).padding(horizontal = 8.dp, vertical = 4.dp))
        Text("RESUMEN GLOBAL", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Panel).clickable(onClick = global).padding(horizontal = 10.dp, vertical = 4.dp))
        if (filtered.isEmpty()) Text("Sin partidos", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 35.dp))
        filtered.chunked(2).forEach { rowRecords ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                rowRecords.forEach { indexed ->
                    val record = indexed.value
                    val saved = record.saved
                    val scoreA = if (sport.isFootball) saved.state.pointsA else saved.state.setsA; val scoreB = if (sport.isFootball) saved.state.pointsB else saved.state.setsB
                    val outcome = when { scoreA > scoreB -> Win; scoreA < scoreB -> Loss; else -> Draw }
                    Column(
                        Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xFF292E2A))
                            .clickable { open(indexed.index) }.padding(5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("$scoreA-$scoreB", color = outcome, fontSize = 15.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(outcome.copy(alpha = .14f)).padding(horizontal = 7.dp, vertical = 1.dp))
                        Text(watchMatchupLabel(saved.config), color = Color.White, fontSize = 7.sp, lineHeight = 9.sp, fontWeight = FontWeight.Bold, maxLines = 2, textAlign = TextAlign.Center)
                        Text(formatLocalDate(record.endedAt, "dd/MM/yy"), color = Color.White, fontSize = 8.sp, lineHeight = 10.sp)
                    }
                }
                if (rowRecords.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun GlobalStatsWatch(records: List<HistoryRecord>, back: () -> Unit) {
    fun scores(record: HistoryRecord) = if (record.saved.config.sport.isFootball) record.saved.state.pointsA to record.saved.state.pointsB else record.saved.state.setsA to record.saved.state.setsB
    val wins = records.count { scores(it).let { (a, b) -> a > b } }; val losses = records.count { scores(it).let { (a, b) -> a < b } }; val draws = records.size - wins - losses
    val setsA = records.sumOf { it.saved.state.setsA }; val setsB = records.sumOf { it.saved.state.setsB }; val games = records.sumOf { it.saved.state.completedGames }
    val total = records.sumOf { (it.endedAt - it.saved.state.startedAt).coerceAtLeast(0) }
    val health = records.map { it.saved.state }.filter { it.averageHeartRate > 0 || it.distanceMeters > 0 || it.calories > 0 || it.steps > 0 }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 25.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("<  RESUMEN", color = Lime, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = back).padding(3.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            WatchGlobalResult("G", wins, Win, Modifier.weight(1f)); WatchGlobalResult("E", draws, Draw, Modifier.weight(1f)); WatchGlobalResult("P", losses, Loss, Modifier.weight(1f))
        }
        Text("${records.size} PARTIDOS", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        if (records.firstOrNull()?.saved?.config?.sport?.isFootball == true) Text("GOLES  ${records.sumOf { it.saved.state.pointsA }}-${records.sumOf { it.saved.state.pointsB }}", color = Muted, fontSize = 9.sp)
        else { Text("SETS  $setsA-$setsB", color = Muted, fontSize = 9.sp); Text("$games GAMES", color = Muted, fontSize = 9.sp) }
        Text("TOTAL  ${formatDuration(total)}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text("PROM.  ${formatDuration(if (records.isEmpty()) 0 else total / records.size)}", color = Muted, fontSize = 9.sp)
        if (health.isNotEmpty()) {
            Text("FC ${health.map { it.averageHeartRate }.filter { it > 0 }.average().toInt()} PPM", color = Lime, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text("%.2f KM · %d PASOS".format(health.sumOf { it.distanceMeters } / 1000, health.sumOf { it.steps }), color = Muted, fontSize = 8.sp)
        }
    }
}

@Composable
private fun WatchGlobalResult(label: String, value: Int, color: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = .15f)).padding(vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Color.White, fontSize = 7.sp)
    }
}

@Composable
private fun HistoryDetail(record: HistoryRecord, onUpdate: (HistoryRecord) -> Unit, onDelete: () -> Unit, onBack: () -> Unit) {
    val saved = record.saved
    val total = (record.endedAt - saved.state.startedAt).coerceAtLeast(0)
    var confirmDelete by remember(record.endedAt) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 26.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text("<  DETALLE", color = Color.White, fontSize = 11.sp, modifier = Modifier.clickable(onClick = onBack))
        Text(formatLocalDate(record.endedAt, "dd/MM/yyyy  HH:mm"), color = Muted, fontSize = 9.sp)
        Text(watchMatchupLabel(saved.config), color = Color.White, fontSize = 9.sp, textAlign = TextAlign.Center)
        Text("RESULTADO  ${if (saved.config.sport.isFootball) "${saved.state.pointsA}-${saved.state.pointsB}" else "${saved.state.setsA}-${saved.state.setsB}"}", color = Lime, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text("TOTAL  ${formatDuration(total)}", color = Color.White, fontSize = 11.sp)
        if (saved.config.sport.isFootball && saved.state.goalEvents.isNotEmpty()) GoalTimelineWatch(saved.state.goalEvents)
        if (!saved.config.sport.isFootball) Row(Modifier.fillMaxWidth().padding(horizontal = 5.dp)) {
            Text("SET", color = Muted, fontSize = 7.sp, modifier = Modifier.weight(1f))
            Text("RESULTADO", color = Muted, fontSize = 7.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1.4f))
            Text("TIEMPO", color = Muted, fontSize = 7.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
        if (!saved.config.sport.isFootball) saved.state.setDurations.forEachIndexed { index, duration -> WatchSetRow(index + 1, saved.state.setScores.getOrElse(index) { "—" }, formatDuration(duration), false) }
        if (!saved.config.sport.isFootball && (saved.state.gamesA != 0 || saved.state.gamesB != 0 || saved.state.pointsA != 0 || saved.state.pointsB != 0)) {
            WatchSetRow(saved.state.setDurations.size + 1, "${saved.state.gamesA}-${saved.state.gamesB}", formatDuration((record.endedAt - saved.state.setStartedAt).coerceAtLeast(0)), true)
        }
        val games = saved.state.gameDurations
        if (!saved.config.sport.isFootball && games.isNotEmpty()) Text("${games.size} games · Prom. ${formatDuration(games.average().toLong())}", color = Muted, fontSize = 9.sp)
        Text(if (saved.state.averageHeartRate > 0) "FC ${saved.state.averageHeartRate.toInt()} · MAX ${saved.state.maxHeartRate.toInt()} PPM" else "FC - · MAX - PPM", color = Lime, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        Text(if (saved.state.distanceMeters > 0 || saved.state.steps > 0) "%s%.2f KM · %d PASOS".format(if (saved.state.distanceEstimated) "~" else "", saved.state.distanceMeters / 1000, saved.state.steps) else "DISTANCIA - · PASOS -", color = Muted, fontSize = 8.sp)
        Text(if (saved.state.calories > 0) "${saved.state.calories.toInt()} KCAL" else "CALORIAS -", color = Muted, fontSize = 8.sp)
        Box(
            Modifier.fillMaxWidth(.72f).height(23.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF512326)).clickable {
                if (confirmDelete) onDelete() else confirmDelete = true
            }, contentAlignment = Alignment.Center
        ) {
            Text(if (confirmDelete) "CONFIRMAR" else "ELIMINAR", color = Color(0xFFFF777D), fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun GoalTimelineWatch(events: List<GoalEvent>) {
    var scoreA = 0
    var scoreB = 0
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Panel).padding(7.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("GOLES", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        events.forEach { goal ->
            if (goal.teamA) scoreA++ else scoreB++
            Row(Modifier.fillMaxWidth()) {
                Text(if (goal.teamA) "MI" else "RIVAL", color = if (goal.teamA) TeamColors[0] else TeamColors[1], fontSize = 7.sp, modifier = Modifier.weight(1f))
                Text("$scoreA-$scoreB", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                val seconds = goal.elapsedMillis.coerceAtLeast(0) / 1000
                Text("${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}", color = Lime, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun WatchSetRow(number: Int, score: String, duration: String, partial: Boolean) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(if (partial) Lime.copy(alpha = .12f) else Panel).padding(horizontal = 5.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${if (partial) "*" else ""}$number", color = if (partial) Lime else Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(score, color = if (partial) Lime else Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.weight(1.4f))
        Text(duration, color = Color.White, fontSize = 8.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    val minutes = seconds / 60
    return "%d:%02d".format(minutes, seconds % 60)
}

private fun formatLocalDate(timestamp: Long, pattern: String): String =
    SimpleDateFormat(pattern, Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("America/Argentina/Buenos_Aires")
    }.format(Date(timestamp))

@Composable
private fun NameField(value: String, color: Color, update: (String) -> Unit) {
    BasicTextField(
        value = value, onValueChange = { update(formatTeamNameInput(it, 14)) }, singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center),
        modifier = Modifier.fillMaxWidth().height(27.dp).clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = .16f)).padding(4.dp)
    )
}

@Composable
private fun Choice(label: String, selected: Boolean, width: Int = 37, click: () -> Unit) {
    Box(
        Modifier.size(width = width.dp, height = 26.dp).clip(RoundedCornerShape(6.dp))
            .background(if (selected) Lime else Panel).clickable(onClick = click),
        contentAlignment = Alignment.Center
    ) { Text(label, color = if (selected) Ink else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun ScoreScreen(config: MatchConfig, state: MatchState, onPoint: (Boolean) -> Unit, onUndo: () -> Unit, onFinish: () -> Unit, locked: Boolean, onLockChange: (Boolean) -> Unit) {
    if (config.sport.isFootball) {
        FootballScoreWatch(config, state, onPoint, onUndo, onFinish, locked, onLockChange)
        return
    }
    val serverA = MatchEngine.serverTeamA(state, config)
    var now by remember(state.startedAt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.startedAt, state.finished) {
        while (!state.finished) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val elapsed = if (state.finished && state.setDurations.isNotEmpty()) state.setDurations.sum() else (now - state.startedAt).coerceAtLeast(0)
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            TinyButton("↶", onUndo, !locked)
            Spacer(Modifier.weight(1f))
            Text(formatDuration(elapsed), color = if (locked) Lime else Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(5.dp))
            LockButton(locked, onLockChange)
            Spacer(Modifier.width(4.dp))
            TinyButton("×", onFinish, !locked)
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().height(132.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            TeamPanel("Mi Equipo", TeamColors[config.colorA], state.gamesA, state.setsA, MatchEngine.pointLabel(state.pointsA, state.pointsB, state.tieBreak, config.advantage), serverA, MatchEngine.serverPlayer(state), config.doubles, state.finished && state.setsA > state.setsB, !state.finished && !locked, { onPoint(true) }, Modifier.weight(1f))
            TeamPanel("Rival", TeamColors[config.colorB], state.gamesB, state.setsB, MatchEngine.pointLabel(state.pointsB, state.pointsA, state.tieBreak, config.advantage), !serverA, MatchEngine.serverPlayer(state), config.doubles, state.finished && state.setsB > state.setsA, !state.finished && !locked, { onPoint(false) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun FootballScoreWatch(config: MatchConfig, state: MatchState, onGoal: (Boolean) -> Unit, onUndo: () -> Unit, onFinish: () -> Unit, locked: Boolean, onLockChange: (Boolean) -> Unit) {
    var now by remember(state.startedAt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.startedAt) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            TinyButton("↶", onUndo, !locked); Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(config.sport.displayName.uppercase(), color = Lime, fontSize = 8.sp, fontWeight = FontWeight.Bold); Text(formatDuration((now - state.startedAt).coerceAtLeast(0)), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.weight(1f)); LockButton(locked, onLockChange); Spacer(Modifier.width(4.dp)); TinyButton("×", onFinish, !locked)
        }
        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth().height(132.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            FootballTeamWatch("Mi Equipo", TeamColors[config.colorA], state.pointsA, !locked, { onGoal(true) }, Modifier.weight(1f))
            FootballTeamWatch("Rival", TeamColors[config.colorB], state.pointsB, !locked, { onGoal(false) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun FootballTeamWatch(name: String, color: Color, goals: Int, enabled: Boolean, click: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = .14f)).clickable(enabled = enabled, onClick = click).padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
        Text(name, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(goals.toString(), color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold)
        Text("+ GOL", color = color, fontSize = 8.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TeamPanel(name: String, color: Color, games: Int, sets: Int, points: String, serving: Boolean, serverPlayer: Int, doubles: Boolean, winner: Boolean, enabled: Boolean, click: () -> Unit, modifier: Modifier) {
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = .13f)).clickable(enabled = enabled, onClick = click).padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.height(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(name, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            if (serving) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(Lime))
                    if (doubles) Text("J$serverPlayer", color = Lime, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(if (winner) "GANÓ" else points, color = Color.White, fontSize = if (winner) 17.sp else 28.sp, fontWeight = FontWeight.Bold)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("GAMES  $games", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("SETS  $sets", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TinyButton(label: String, click: () -> Unit, enabled: Boolean = true) {
    Box(Modifier.size(23.dp).clip(CircleShape).background(Color(0xFF292E2A)).clickable(enabled = enabled, onClick = click), contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled) Color.White else Muted, fontSize = 13.sp)
    }
}

@Composable
private fun LockButton(locked: Boolean, update: (Boolean) -> Unit) {
    val modifier = if (locked) Modifier.pointerInput(Unit) {
        detectTapGestures(onLongPress = { update(false) })
    } else Modifier.clickable { update(true) }
    Box(Modifier.size(23.dp).clip(CircleShape).background(if (locked) Lime else Color(0xFF292E2A)).then(modifier), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(12.dp)) {
            val iconColor = if (locked) Ink else Color.White
            drawRoundRect(iconColor, topLeft = Offset(2f, 5f), size = Size(size.width - 4f, size.height - 6f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f, 1.5f))
            drawArc(iconColor, startAngle = 180f, sweepAngle = 180f, useCenter = false,
                topLeft = Offset(3.5f, 1f), size = Size(size.width - 7f, 8f), style = Stroke(width = 1.8f))
        }
    }
}

@Composable
private fun ConfirmScreen(onCancel: () -> Unit, onConfirm: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("¿Terminar partido?", color = Color.White, fontSize = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Choice("NO", false, click = onCancel)
            Choice("SI", true, click = onConfirm)
        }
    }
}
