package com.ezequiel.padelcounter

import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { MaterialTheme { PadelApp(loadSaved(), loadHistory(), ::save, ::saveHistory) } }
    }

    private fun save(saved: SavedMatch?) {
        prefs.edit().apply {
            if (saved == null) remove("current") else putString("current", saved.toJson().toString())
        }.apply()
    }

    private fun loadSaved(): SavedMatch? = runCatching { savedFromJson(JSONObject(prefs.getString("current", null) ?: return null)) }.getOrNull()

    private fun loadHistory(): List<HistoryRecord> = runCatching {
        val array = JSONArray(prefs.getString("history", "[]"))
        (0 until array.length()).map { index ->
            val json = array.getJSONObject(index)
            HistoryRecord(json.getLong("endedAt"), savedFromJson(json))
        }
    }.getOrDefault(emptyList())

    private fun saveHistory(records: List<HistoryRecord>) {
        val array = JSONArray()
        records.take(50).forEach { record -> array.put(record.saved.toJson().put("endedAt", record.endedAt)) }
        prefs.edit().putString("history", array.toString()).apply()
    }
}

private fun savedFromJson(json: JSONObject) = SavedMatch(
    MatchConfig(json.getString("a"), json.getString("b"), json.getInt("max"), json.getBoolean("adv"), json.optInt("colorA", 2), json.optInt("colorB", 1), json.optBoolean("doubles", true), json.optBoolean("serverA", true)),
    MatchState(
        pointsA = json.getInt("pa"), pointsB = json.getInt("pb"), gamesA = json.getInt("ga"), gamesB = json.getInt("gb"),
        setsA = json.getInt("sa"), setsB = json.getInt("sb"), finished = json.getBoolean("done"),
        completedGames = json.optInt("completedGames", 0), startedAt = json.optLong("startedAt", System.currentTimeMillis()),
        setStartedAt = json.optLong("setStartedAt", json.optLong("startedAt", System.currentTimeMillis())),
        gameStartedAt = json.optLong("gameStartedAt", json.optLong("startedAt", System.currentTimeMillis())),
        setDurations = json.optJSONArray("setDurations").toLongList(), gameDurations = json.optJSONArray("gameDurations").toLongList()
    )
)

data class SavedMatch(val config: MatchConfig, val state: MatchState) {
    fun toJson() = JSONObject().apply {
        put("a", config.teamA); put("b", config.teamB); put("max", config.maxSets); put("adv", config.advantage)
        put("colorA", config.colorA); put("colorB", config.colorB)
        put("doubles", config.doubles); put("serverA", config.initialServerA)
        put("pa", state.pointsA); put("pb", state.pointsB); put("ga", state.gamesA); put("gb", state.gamesB)
        put("sa", state.setsA); put("sb", state.setsB); put("done", state.finished)
        put("completedGames", state.completedGames)
        put("startedAt", state.startedAt); put("setStartedAt", state.setStartedAt); put("gameStartedAt", state.gameStartedAt)
        put("setDurations", JSONArray(state.setDurations)); put("gameDurations", JSONArray(state.gameDurations))
    }
}

data class HistoryRecord(val endedAt: Long, val saved: SavedMatch)

private fun JSONArray?.toLongList(): List<Long> = if (this == null) emptyList() else (0 until length()).map { getLong(it) }

private val Ink = Color(0xFF0E110F)
private val Lime = Color(0xFFD7FF4F)
private val Cyan = Color(0xFF4FA3FF)
private val Muted = Color(0xFF9EA6A0)
private val TeamColors = listOf(Lime, Cyan, Color(0xFFFF6B6B), Color(0xFFFFC857), Color(0xFFB892FF))

@Composable
private fun PadelApp(initial: SavedMatch?, initialHistory: List<HistoryRecord>, persist: (SavedMatch?) -> Unit, persistHistory: (List<HistoryRecord>) -> Unit) {
    var screen by remember { mutableStateOf(if (initial == null) "setup" else "score") }
    var config by remember { mutableStateOf(initial?.config ?: MatchConfig()) }
    var state by remember { mutableStateOf(initial?.state ?: MatchState()) }
    val history = remember { mutableStateListOf<MatchState>() }
    val records = remember { mutableStateListOf<HistoryRecord>().apply { addAll(initialHistory) } }
    var selectedRecord by remember { mutableStateOf<Int?>(null) }
    var locked by remember { mutableStateOf(false) }
    val view = LocalView.current

    Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
        when (screen) {
            "setup" -> SetupScreen(config, { config = it }, { screen = "teams" }, { screen = "history" }, {
                val started = config.copy(
                    teamA = config.teamA.ifBlank { "Mi Equipo" },
                    teamB = config.teamB.ifBlank { "Rival" },
                )
                config = started
                state = MatchState()
                history.clear()
                persist(SavedMatch(started, state))
                screen = "score"
            })
            "teams" -> TeamsScreen(config, { config = it }, { screen = "setup" })
            "history" -> HistoryScreen(records, { index -> selectedRecord = index; screen = "detail" }, { screen = "setup" })
            "detail" -> selectedRecord?.let { index ->
                HistoryDetail(records[index],
                    onUpdate = { updated -> records[index] = updated; persistHistory(records.toList()) },
                    onDelete = { records.removeAt(index); persistHistory(records.toList()); selectedRecord = null; screen = "history" },
                    onBack = { screen = "history" })
            }
            "confirm" -> ConfirmScreen(
                onCancel = { screen = "score" },
                onConfirm = {
                    if (state.finished) {
                        records.add(0, HistoryRecord(System.currentTimeMillis(), SavedMatch(config, state)))
                        persistHistory(records.toList())
                        selectedRecord = 0
                    }
                    val wasFinished = state.finished
                    persist(null); config = MatchConfig(); state = MatchState(); history.clear(); locked = false
                    screen = if (wasFinished) "detail" else "setup"
                }
            )
            else -> ScoreScreen(config, state,
                onPoint = { teamA ->
                    history.add(state)
                    val previous = state
                    state = MatchEngine.point(state, teamA, config)
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
private fun SetupScreen(config: MatchConfig, update: (MatchConfig) -> Unit, teams: () -> Unit, history: () -> Unit, start: () -> Unit) {
    Column(
        Modifier.fillMaxSize().pointerInput(Unit) {
            var drag = 0f
            detectVerticalDragGestures(
                onDragStart = { drag = 0f },
                onVerticalDrag = { _, amount -> drag += amount },
                onDragEnd = { if (drag < -45f) teams() else if (drag > 45f) history() }
            )
        }.padding(horizontal = 28.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text("PUNTO PADEL", color = Lime, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Sets", color = Muted, fontSize = 10.sp, maxLines = 1, modifier = Modifier.width(36.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(1, 3, 5).forEach { n -> Choice(n.toString(), config.maxSets == n) { update(config.copy(maxSets = n)) } }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Ventaja", color = Muted, fontSize = 10.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Choice("SI", config.advantage) { update(config.copy(advantage = true)) }
                Choice("NO", !config.advantage) { update(config.copy(advantage = false)) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Modo", color = Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Choice("1v1", !config.doubles) { update(config.copy(doubles = false)) }
                Choice("2v2", config.doubles) { update(config.copy(doubles = true)) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Saque", color = Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Choice("A", config.initialServerA) { update(config.copy(initialServerA = true)) }
                Choice("B", !config.initialServerA) { update(config.copy(initialServerA = false)) }
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
        Modifier.fillMaxSize().pointerInput(Unit) {
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
        Text("EQUIPOS", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(onClick = back).padding(3.dp))
        NameField(config.teamA, TeamColors[config.colorA]) { update(config.copy(teamA = it)) }
        ColorPicker(config.colorA) { update(config.copy(colorA = it)) }
        NameField(config.teamB, TeamColors[config.colorB]) { update(config.copy(teamB = it)) }
        ColorPicker(config.colorB) { update(config.copy(colorB = it)) }
        Box(
            Modifier.fillMaxWidth(.72f).height(22.dp).clip(RoundedCornerShape(6.dp)).background(Lime).clickable(onClick = back),
            contentAlignment = Alignment.Center
        ) { Text("LISTO", color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun ColorPicker(selected: Int, update: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TeamColors.forEachIndexed { index, color ->
            Box(
                Modifier.size(if (selected == index) 22.dp else 18.dp).clip(CircleShape).background(color)
                    .clickable { update(index) }
            )
        }
    }
}

@Composable
private fun HistoryScreen(records: List<HistoryRecord>, open: (Int) -> Unit, back: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 25.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text("<  HISTORIAL", color = Lime, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(onClick = back).padding(horizontal = 8.dp, vertical = 4.dp))
        if (records.isEmpty()) Text("Sin partidos", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 35.dp))
        records.chunked(2).forEachIndexed { rowIndex, rowRecords ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                rowRecords.forEachIndexed { columnIndex, record ->
                    val saved = record.saved
                    Column(
                        Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xFF292E2A))
                            .clickable { open(rowIndex * 2 + columnIndex) }.padding(5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("${saved.state.setsA}-${saved.state.setsB}", color = Lime, fontSize = 15.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold)
                        Text(saved.config.teamA, color = TeamColors[saved.config.colorA], fontSize = 8.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(saved.config.teamB, color = TeamColors[saved.config.colorB], fontSize = 8.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(formatLocalDate(record.endedAt, "dd/MM/yy"), color = Color.White, fontSize = 8.sp, lineHeight = 10.sp)
                    }
                }
                if (rowRecords.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HistoryDetail(record: HistoryRecord, onUpdate: (HistoryRecord) -> Unit, onDelete: () -> Unit, onBack: () -> Unit) {
    val saved = record.saved
    val total = saved.state.setDurations.sum().takeIf { it > 0 } ?: (record.endedAt - saved.state.startedAt).coerceAtLeast(0)
    var confirmDelete by remember(record.endedAt) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 26.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text("<  DETALLE", color = Color.White, fontSize = 11.sp, modifier = Modifier.clickable(onClick = onBack))
        Text(formatLocalDate(record.endedAt, "dd/MM/yyyy  HH:mm"), color = Muted, fontSize = 9.sp)
        NameField(saved.config.teamA, TeamColors[saved.config.colorA]) { onUpdate(record.copy(saved = saved.copy(config = saved.config.copy(teamA = it)))) }
        NameField(saved.config.teamB, TeamColors[saved.config.colorB]) { onUpdate(record.copy(saved = saved.copy(config = saved.config.copy(teamB = it)))) }
        Text("RESULTADO  ${saved.state.setsA}-${saved.state.setsB}", color = Lime, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text("TOTAL  ${formatDuration(total)}", color = Color.White, fontSize = 11.sp)
        saved.state.setDurations.forEachIndexed { index, duration -> Text("Set ${index + 1}  ${formatDuration(duration)}", color = Muted, fontSize = 10.sp) }
        val games = saved.state.gameDurations
        if (games.isNotEmpty()) Text("${games.size} games · Prom. ${formatDuration(games.average().toLong())}", color = Muted, fontSize = 9.sp)
        Box(
            Modifier.fillMaxWidth(.72f).height(23.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF512326)).clickable {
                if (confirmDelete) onDelete() else confirmDelete = true
            }, contentAlignment = Alignment.Center
        ) {
            Text(if (confirmDelete) "CONFIRMAR" else "ELIMINAR", color = Color(0xFFFF777D), fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
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
        value = value, onValueChange = { update(it.take(14)) }, singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center),
        modifier = Modifier.fillMaxWidth().height(27.dp).clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = .16f)).padding(4.dp)
    )
}

@Composable
private fun Choice(label: String, selected: Boolean, click: () -> Unit) {
    Box(
        Modifier.size(width = 37.dp, height = 26.dp).clip(RoundedCornerShape(6.dp))
            .background(if (selected) Lime else Color(0xFF292E2A)).clickable(onClick = click),
        contentAlignment = Alignment.Center
    ) { Text(label, color = if (selected) Ink else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun ScoreScreen(config: MatchConfig, state: MatchState, onPoint: (Boolean) -> Unit, onUndo: () -> Unit, onFinish: () -> Unit, locked: Boolean, onLockChange: (Boolean) -> Unit) {
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
            TeamPanel(config.teamA, TeamColors[config.colorA], state.gamesA, state.setsA, MatchEngine.pointLabel(state.pointsA, state.pointsB, state.tieBreak, config.advantage), serverA, MatchEngine.serverPlayer(state), config.doubles, state.finished && state.setsA > state.setsB, !state.finished && !locked, { onPoint(true) }, Modifier.weight(1f))
            TeamPanel(config.teamB, TeamColors[config.colorB], state.gamesB, state.setsB, MatchEngine.pointLabel(state.pointsB, state.pointsA, state.tieBreak, config.advantage), !serverA, MatchEngine.serverPlayer(state), config.doubles, state.finished && state.setsB > state.setsA, !state.finished && !locked, { onPoint(false) }, Modifier.weight(1f))
        }
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
            Choice("NO", false, onCancel)
            Choice("SI", true, onConfirm)
        }
    }
}
