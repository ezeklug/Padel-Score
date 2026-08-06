package com.ezequiel.padelcounter.mobile

import android.content.*
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Scoreboard
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Undo
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ezequiel.padelcounter.MatchConfig
import com.ezequiel.padelcounter.MatchEngine
import com.ezequiel.padelcounter.MatchState
import com.ezequiel.padelcounter.formatTeamNameInput
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.io.File
import java.io.FileOutputStream
import java.util.*
import androidx.core.content.FileProvider

private val Ink = Color(0xFFF2F4F7)
private val Canvas = Color(0xFF111820)
private val Panel = Color(0xFF1B2530)
private val Accent = Color(0xFF4EB3D3)
private val Coral = Color(0xFFE6534E)
private val Muted = Color(0xFFA5B0BD)
private val Divider = Color(0xFF344250)
private val Win = Color(0xFF35B779)
private val Draw = Color(0xFFF2B84B)
private val Loss = Color(0xFFE45C62)
private val Palette = listOf(Coral, Color(0xFF2878B5), Color(0xFF159A82), Color(0xFFE9A23B), Color(0xFF7959B8))

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("match", MODE_PRIVATE) }
    private val receiver = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) = recreate() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        migratePalette()
        val openLatest = prefs.getBoolean("open_latest_detail", false)
        prefs.edit().remove("open_latest_detail").apply()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent, onPrimary = Color(0xFF07151B), background = Canvas,
                    surface = Panel, onSurface = Ink, outline = Divider, error = Coral
                )
            ) { PhoneApp(loadCurrent(), loadHistory(), openLatest, ::persist) }
        }
    }
    override fun onStart() { super.onStart(); registerReceiver(receiver, IntentFilter(ACTION_SYNCED), RECEIVER_NOT_EXPORTED) }
    override fun onStop() { unregisterReceiver(receiver); super.onStop() }

    private fun loadCurrent() = runCatching { prefs.getString("current", null)?.let { PhoneCodec.matchFromJson(org.json.JSONObject(it)) } }.getOrNull()
    private fun loadHistory() = runCatching { PhoneCodec.historyFromJson(prefs.getString("history", "[]") ?: "[]") }.getOrDefault(emptyList())
    private fun persist(current: PhoneMatch?, history: List<PhoneRecord>) {
        prefs.edit().apply {
            if (current == null) remove("current") else putString("current", PhoneCodec.matchToJson(current).toString())
            putString("history", PhoneCodec.historyToJson(history))
        }.apply()
        MobileSync.publish(this)
    }

    private fun migratePalette() {
        if (prefs.getBoolean("palette_v2", false)) return
        fun migrate(json: org.json.JSONObject) { if (json.optInt("colorA", 2) == 2) json.put("colorA", 0) }
        prefs.edit().apply {
            prefs.getString("current", null)?.let { runCatching { org.json.JSONObject(it).also(::migrate).toString() }.getOrNull()?.let { value -> putString("current", value) } }
            prefs.getString("history", null)?.let { raw -> runCatching { org.json.JSONArray(raw).also { array -> (0 until array.length()).forEach { migrate(array.getJSONObject(it)) } }.toString() }.getOrNull()?.let { value -> putString("history", value) } }
            putBoolean("palette_v2", true)
        }.apply()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneApp(initial: PhoneMatch?, initialHistory: List<PhoneRecord>, openLatest: Boolean, persist: (PhoneMatch?, List<PhoneRecord>) -> Unit) {
    var current by remember { mutableStateOf(initial) }
    var config by remember { mutableStateOf(initial?.config ?: MatchConfig()) }
    val records = remember { mutableStateListOf<PhoneRecord>().apply { addAll(initialHistory) } }
    var tab by remember { mutableIntStateOf(if (openLatest && initialHistory.isNotEmpty()) 2 else if (initial == null) 0 else 1) }
    var detailIndex by remember { mutableStateOf<Int?>(if (openLatest && initialHistory.isNotEmpty()) 0 else null) }
    var showGlobal by remember { mutableStateOf(false) }
    val undo = remember { mutableStateListOf<MatchState>() }

    Scaffold(
        containerColor = Canvas,
        topBar = {
            TopAppBar(
                title = { Text("Punto Padel", color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp) },
                actions = {
                    Surface(color = if (current == null) Divider else Color(0xFF153E42), shape = RoundedCornerShape(20.dp), modifier = Modifier.padding(end = 16.dp)) {
                        Text(if (current == null) "Sin partido" else "En juego", color = if (current == null) Muted else Color(0xFF65D6C0), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Panel)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Panel, tonalElevation = 8.dp) {
                NavigationBarItem(tab == 0, { tab = 0; detailIndex = null; showGlobal = false }, icon = { Icon(Icons.Default.AddCircle, null) }, label = { Text("Nuevo") }, colors = navColors())
                NavigationBarItem(tab == 1, { tab = 1; detailIndex = null; showGlobal = false }, icon = { Icon(Icons.Default.Scoreboard, null) }, label = { Text("Tanteador") }, colors = navColors())
                NavigationBarItem(tab == 2, { tab = 2; detailIndex = null; showGlobal = false }, icon = { Icon(Icons.Default.History, null) }, label = { Text("Historial") }, colors = navColors())
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(Canvas)) {
            when {
                tab == 0 -> SetupPhone(config, { config = it }) {
                    current = PhoneMatch(config, MatchState()); undo.clear(); persist(current, records); tab = 1
                }
                tab == 1 -> current?.let { match ->
                    ScorePhone(match, undo.isNotEmpty(),
                        point = { teamA -> undo.add(match.state); current = match.copy(state = MatchEngine.point(match.state, teamA, match.config)); persist(current, records) },
                        undo = { if (undo.isNotEmpty()) { current = match.copy(state = undo.removeAt(undo.lastIndex)); persist(current, records) } },
                        finish = {
                            records.add(0, PhoneRecord(System.currentTimeMillis(), match.copy(state = match.state.copy(finished = true))))
                            current = null; config = MatchConfig(); undo.clear(); persist(null, records); tab = 2; detailIndex = 0
                        })
                } ?: EmptyScore { tab = 0 }
                detailIndex != null -> DetailPhone(records[detailIndex!!],
                    update = { records[detailIndex!!] = it; persist(current, records) },
                    delete = { records.removeAt(detailIndex!!); persist(current, records); detailIndex = null },
                    back = { detailIndex = null })
                showGlobal -> GlobalStatsPhone(records) { showGlobal = false }
                else -> HistoryPhone(records, { detailIndex = it }) { showGlobal = true }
            }
        }
    }
}

@Composable
private fun navColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = Color.White, selectedTextColor = Accent, indicatorColor = Accent,
    unselectedIconColor = Muted, unselectedTextColor = Muted
)

@Composable
private fun SetupPhone(config: MatchConfig, update: (MatchConfig) -> Unit, start: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Column { Text("NUEVO PARTIDO", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold); Text("Configura los equipos", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold) } }
        item { OutlinedTextField(config.teamA, { update(config.copy(teamA = formatTeamNameInput(it))) }, label = { Text("Mi equipo") }, singleLine = true, shape = RoundedCornerShape(7.dp), modifier = Modifier.fillMaxWidth()) }
        item { ColorRow(config.colorA, config.colorB) { update(config.copy(colorA = it)) } }
        item { OutlinedTextField(config.teamB, { update(config.copy(teamB = formatTeamNameInput(it))) }, label = { Text("Rival") }, singleLine = true, shape = RoundedCornerShape(7.dp), modifier = Modifier.fillMaxWidth()) }
        item { ColorRow(config.colorB, config.colorA) { update(config.copy(colorB = it)) } }
        item { OptionRow("Sets", listOf("1", "3", "5", "Libre"), listOf(1, 3, 5, 0).indexOf(config.maxSets)) { update(config.copy(maxSets = listOf(1, 3, 5, 0)[it])) } }
        item { OptionRow("Ventaja", listOf("Si", "No"), if (config.advantage) 0 else 1) { update(config.copy(advantage = it == 0)) } }
        item { OptionRow("Modalidad", listOf("1 vs 1", "2 vs 2"), if (config.doubles) 1 else 0) { update(config.copy(doubles = it == 1)) } }
        item { OptionRow("Primer saque", listOf(config.teamA, config.teamB), if (config.initialServerA) 0 else 1) { update(config.copy(initialServerA = it == 0)) } }
        item { Button(start, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(8.dp)) { Icon(Icons.Default.Scoreboard, null); Spacer(Modifier.width(8.dp)); Text("Empezar partido", fontWeight = FontWeight.Bold) } }
    }
}

@Composable
private fun ColorRow(selected: Int, unavailable: Int, update: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Palette.forEachIndexed { index, color ->
            val chosen = index == selected
            Box(
                Modifier.size(36.dp).clip(CircleShape)
                    .background(if (chosen) Color.White else Color.Transparent)
                    .padding(if (chosen) 4.dp else 6.dp).clip(CircleShape)
                    .background(if (index == unavailable) color.copy(alpha = .22f) else color)
                    .clickable(enabled = index != unavailable) { update(index) }
            )
        }
    }
}

@Composable
private fun OptionRow(label: String, options: List<String>, selected: Int, update: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label, color = Muted, fontSize = 13.sp)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) { options.forEachIndexed { i, text -> SegmentedButton(i == selected, { update(i) }, SegmentedButtonDefaults.itemShape(i, options.size)) { Text(text, maxLines = 1) } } }
    }
}

@Composable
private fun ScorePhone(match: PhoneMatch, canUndo: Boolean, point: (Boolean) -> Unit, undo: () -> Unit, finish: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(match.state.finished) { while (!match.state.finished) { now = System.currentTimeMillis(); delay(1000) } }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (match.state.tieBreak) "TIE-BREAK" else "PARTIDO EN CURSO", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(formatDuration((now - match.state.startedAt).coerceAtLeast(0)), color = Ink, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
            }
            IconButton(undo, enabled = canUndo, modifier = Modifier.background(Panel, CircleShape)) { Icon(Icons.Default.Undo, "Deshacer", tint = if (canUndo) Ink else Muted) }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(finish, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 14.dp)) {
                Icon(if (match.state.finished) Icons.Default.Save else Icons.Default.StopCircle, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text(if (match.state.finished) "Guardar" else "Finalizar", fontWeight = FontWeight.Bold)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PhoneTeam(match, true, point, Modifier.weight(1f))
            PhoneTeam(match, false, point, Modifier.weight(1f))
        }
        Surface(Modifier.fillMaxWidth(), color = Panel, shape = RoundedCornerShape(8.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Divider)) {
            Row(Modifier.padding(vertical = 14.dp)) {
                MatchStat("Games", "${match.state.gamesA} - ${match.state.gamesB}", Modifier.weight(1f))
                Box(Modifier.width(1.dp).height(40.dp).background(Divider))
                MatchStat("Sets", "${match.state.setsA} - ${match.state.setsB}", Modifier.weight(1f))
            }
        }
        Text("Toca el equipo que gana el punto", color = Muted, fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
private fun MatchStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Ink, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        Text(label, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun PhoneTeam(match: PhoneMatch, teamA: Boolean, point: (Boolean) -> Unit, modifier: Modifier) {
    val s = match.state; val c = match.config
    val serving = MatchEngine.serverTeamA(s, c) == teamA
    val color = Palette[if (teamA) c.colorA else c.colorB]
    val name = if (teamA) c.teamA else c.teamB
    val points = MatchEngine.pointLabel(if (teamA) s.pointsA else s.pointsB, if (teamA) s.pointsB else s.pointsA, s.tieBreak, c.advantage)
    Column(modifier.height(280.dp).shadow(2.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)).background(Panel).clickable(enabled = !s.finished) { point(teamA) }.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(color))
        Spacer(Modifier.height(18.dp))
        Text(name, color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (serving) Surface(color = color.copy(alpha = .12f), shape = CircleShape) { Text(if (c.doubles) "Saca J${MatchEngine.serverPlayer(s)}" else "Saque", color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)) }
        else Spacer(Modifier.height(26.dp))
        Spacer(Modifier.weight(1f))
        Text(if (s.finished && (if (teamA) s.setsA > s.setsB else s.setsB > s.setsA)) "GANÓ" else points, color = Ink, fontSize = 52.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.weight(1f))
        Text("TOCAR PARA SUMAR", color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable private fun EmptyScore(open: () -> Unit) { Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text("No hay un partido activo", color = Muted); Button(open, Modifier.padding(top = 16.dp)) { Text("Crear partido") } } }

@Composable
private fun HistoryPhone(records: List<PhoneRecord>, open: (Int) -> Unit, global: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.Bottom) { Column(Modifier.weight(1f)) { Text("HISTORIAL", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold); Text("Partidos jugados", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold) }; FilledTonalButton(global, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 12.dp)) { Icon(Icons.Default.Analytics, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Resumen") } } }
        if (records.isEmpty()) item { Text("Todavia no hay partidos finalizados.", color = Muted) }
        itemsIndexed(records) { index, record ->
            val m = record.match
            val outcome = when { m.state.setsA > m.state.setsB -> Win; m.state.setsA < m.state.setsB -> Loss; else -> Draw }
            Row(Modifier.fillMaxWidth().height(76.dp).clip(RoundedCornerShape(7.dp)).background(Panel).clickable { open(index) }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(6.dp)).background(outcome.copy(alpha = .18f)), contentAlignment = Alignment.Center) { Text("${m.state.setsA}-${m.state.setsB}", color = outcome, fontSize = 17.sp, fontWeight = FontWeight.Black) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) { Text("${m.config.teamA} vs ${m.config.teamB}", color = Ink, fontWeight = FontWeight.Bold, maxLines = 1); Text(formatDate(record.endedAt), color = Muted, fontSize = 12.sp) }
                Text("Ver", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun GlobalStatsPhone(records: List<PhoneRecord>, back: () -> Unit) {
    val wins = records.count { it.match.state.setsA > it.match.state.setsB }; val losses = records.count { it.match.state.setsA < it.match.state.setsB }; val draws = records.size - wins - losses
    val setsWon = records.sumOf { it.match.state.setsA }; val setsLost = records.sumOf { it.match.state.setsB }
    val games = records.sumOf { it.match.state.completedGames }; val total = records.sumOf { (it.endedAt - it.match.state.startedAt).coerceAtLeast(0) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { TextButton(back) { Icon(Icons.Default.ArrowBack, null); Spacer(Modifier.width(6.dp)); Text("Historial") } }
        item { Text("Estadisticas globales", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold) }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { GlobalResult("Ganados", wins, Win, Modifier.weight(1f)); GlobalResult("Empates", draws, Draw, Modifier.weight(1f)); GlobalResult("Perdidos", losses, Loss, Modifier.weight(1f)) } }
        item { Surface(Modifier.fillMaxWidth(), color = Panel, shape = RoundedCornerShape(8.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Divider)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { GlobalLine("Partidos jugados", records.size.toString()); GlobalLine("Sets", "$setsWon - $setsLost"); GlobalLine("Games completados", games.toString()); GlobalLine("Tiempo en cancha", formatDuration(total)); GlobalLine("Promedio por partido", formatDuration(if (records.isEmpty()) 0 else total / records.size)) } } }
    }
}

@Composable private fun GlobalResult(label: String, value: Int, color: Color, modifier: Modifier) { Column(modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = .14f)).padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(value.toString(), color = color, fontSize = 28.sp, fontWeight = FontWeight.Black); Text(label, color = Ink, fontSize = 12.sp) } }
@Composable private fun GlobalLine(label: String, value: String) { Row(Modifier.fillMaxWidth()) { Text(label, color = Muted, modifier = Modifier.weight(1f)); Text(value, color = Ink, fontWeight = FontWeight.Bold) } }

@Composable
private fun DetailPhone(record: PhoneRecord, update: (PhoneRecord) -> Unit, delete: () -> Unit, back: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }; val m = record.match; val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { TextButton(back) { Icon(Icons.Default.ArrowBack, null); Spacer(Modifier.width(6.dp)); Text("Volver") }; Spacer(Modifier.weight(1f)); FilledTonalIconButton(onClick = { saveMatchImage(context, record) }) { Icon(Icons.Default.Download, "Guardar en Galeria") }; Spacer(Modifier.width(8.dp)); FilledTonalIconButton(onClick = { shareMatchImage(context, record) }) { Icon(Icons.Default.Share, "Compartir partido") } } }
        item { Text(formatDate(record.endedAt), color = Muted) }
        item { OutlinedTextField(m.config.teamA, { update(record.copy(match = m.copy(config = m.config.copy(teamA = formatTeamNameInput(it))))) }, label = { Text("Equipo A") }, singleLine = true, shape = RoundedCornerShape(7.dp), modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(m.config.teamB, { update(record.copy(match = m.copy(config = m.config.copy(teamB = formatTeamNameInput(it))))) }, label = { Text("Equipo B") }, singleLine = true, shape = RoundedCornerShape(7.dp), modifier = Modifier.fillMaxWidth()) }
        item { Text("${m.state.setsA} - ${m.state.setsB}", color = Accent, fontSize = 40.sp, fontWeight = FontWeight.Black) }
        item { MatchStatsTable(record) }
        item { OutlinedButton({ if (confirm) delete() else confirm = true }, shape = RoundedCornerShape(7.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF777D))) { Icon(Icons.Default.Delete, null); Spacer(Modifier.width(7.dp)); Text(if (confirm) "Confirmar eliminacion" else "Eliminar partido") } }
    }
}

@Composable
private fun MatchStatsTable(record: PhoneRecord) {
    val state = record.match.state
    val hasPartial = state.gamesA != 0 || state.gamesB != 0 || state.pointsA != 0 || state.pointsB != 0
    val partialPoints = "${MatchEngine.pointLabel(state.pointsA, state.pointsB, state.tieBreak, record.match.config.advantage)}-${MatchEngine.pointLabel(state.pointsB, state.pointsA, state.tieBreak, record.match.config.advantage)}"
    Surface(Modifier.fillMaxWidth(), color = Panel, shape = RoundedCornerShape(8.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Divider)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp)) {
                Text("SET", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(.8f))
                Text("RESULTADO", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.2f), textAlign = TextAlign.Center)
                Text("DURACION", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            }
            state.setDurations.forEachIndexed { index, duration -> SetStatsRow(index + 1, state.setScores.getOrElse(index) { "—" }, formatDuration(duration), false) }
            if (hasPartial) SetStatsRow(state.setDurations.size + 1, "${state.gamesA}-${state.gamesB} · $partialPoints", formatDuration((record.endedAt - state.setStartedAt).coerceAtLeast(0)), true)
            Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Tiempo total", color = Muted, fontSize = 12.sp); Text("${state.gameDurations.size} games jugados", color = Muted, fontSize = 11.sp) }
                Text(formatDuration((record.endedAt - state.startedAt).coerceAtLeast(0)), color = Ink, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

@Composable
private fun SetStatsRow(number: Int, score: String, duration: String, partial: Boolean) {
    Row(Modifier.fillMaxWidth().background(if (partial) Accent.copy(alpha = .08f) else Color.Transparent).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(.8f)) { Text("Set $number", color = Ink, fontWeight = FontWeight.Bold); if (partial) Text("En curso", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
        Text(score, color = if (partial) Accent else Ink, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1.2f), textAlign = TextAlign.Center)
        Text(duration, color = Ink, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

private fun formatDuration(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
private fun createMatchImage(context: Context, record: PhoneRecord): File {
    val bitmap = Bitmap.createBitmap(1080, 1350, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap); val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val state = record.match.state; val config = record.match.config
    val shareColors = listOf(0xFFE6534E.toInt(), 0xFF2878B5.toInt(), 0xFF159A82.toInt(), 0xFFE9A23B.toInt(), 0xFF7959B8.toInt())
    fun text(value: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) {
        paint.color = color; paint.textSize = size; paint.textAlign = align
        paint.typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        canvas.drawText(value, x, y, paint)
    }
    canvas.drawColor(android.graphics.Color.rgb(17, 24, 32))
    paint.color = android.graphics.Color.rgb(27, 37, 48); canvas.drawRoundRect(64f, 64f, 1016f, 1286f, 28f, 28f, paint)
    text("PUNTO PADEL", 96f, 135f, 34f, android.graphics.Color.rgb(78, 179, 211), true)
    text(formatDate(record.endedAt), 984f, 135f, 28f, android.graphics.Color.rgb(165, 176, 189), align = Paint.Align.RIGHT)
    text("RESULTADO FINAL", 540f, 235f, 28f, android.graphics.Color.rgb(165, 176, 189), true, Paint.Align.CENTER)
    text("${state.setsA}  -  ${state.setsB}", 540f, 355f, 104f, android.graphics.Color.WHITE, true, Paint.Align.CENTER)
    text(config.teamA, 280f, 455f, 42f, shareColors.getOrElse(config.colorA) { shareColors[0] }, true, Paint.Align.CENTER)
    text(config.teamB, 800f, 455f, 42f, shareColors.getOrElse(config.colorB) { shareColors[1] }, true, Paint.Align.CENTER)
    paint.color = android.graphics.Color.rgb(52, 66, 80); canvas.drawRect(96f, 520f, 984f, 522f, paint)
    text("DURACION", 120f, 610f, 26f, android.graphics.Color.rgb(165, 176, 189), true)
    text(formatDuration((record.endedAt - state.startedAt).coerceAtLeast(0)), 960f, 610f, 48f, android.graphics.Color.WHITE, true, Paint.Align.RIGHT)
    text("SET", 120f, 690f, 24f, android.graphics.Color.rgb(165, 176, 189), true)
    text("RESULTADO", 540f, 690f, 24f, android.graphics.Color.rgb(165, 176, 189), true, Paint.Align.CENTER)
    text("DURACION", 960f, 690f, 24f, android.graphics.Color.rgb(165, 176, 189), true, Paint.Align.RIGHT)
    var y = 755f
    state.setDurations.take(7).forEachIndexed { index, duration ->
        text("Set ${index + 1}", 120f, y, 32f, android.graphics.Color.WHITE, true)
        text(state.setScores.getOrElse(index) { "—" }, 540f, y, 34f, android.graphics.Color.WHITE, true, Paint.Align.CENTER)
        text(formatDuration(duration), 960f, y, 32f, android.graphics.Color.rgb(165, 176, 189), align = Paint.Align.RIGHT); y += 66f
    }
    if (state.gamesA != 0 || state.gamesB != 0 || state.pointsA != 0 || state.pointsB != 0) {
        val pa = MatchEngine.pointLabel(state.pointsA, state.pointsB, state.tieBreak, config.advantage); val pb = MatchEngine.pointLabel(state.pointsB, state.pointsA, state.tieBreak, config.advantage)
        text("Set ${state.setDurations.size + 1}*", 120f, y, 32f, android.graphics.Color.rgb(78, 179, 211), true)
        text("${state.gamesA}-${state.gamesB} · $pa-$pb", 540f, y, 32f, android.graphics.Color.rgb(78, 179, 211), true, Paint.Align.CENTER)
        text(formatDuration((record.endedAt - state.setStartedAt).coerceAtLeast(0)), 960f, y, 32f, android.graphics.Color.rgb(78, 179, 211), true, Paint.Align.RIGHT)
    }
    text("${state.gameDurations.size} games jugados", 540f, 1215f, 28f, android.graphics.Color.rgb(165, 176, 189), align = Paint.Align.CENTER)
    val directory = File(context.cacheDir, "shared_matches").apply { mkdirs() }; val file = File(directory, "punto-padel-${record.endedAt}.png")
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    return file
}

private fun shareMatchImage(context: Context, record: PhoneRecord) {
    val file = createMatchImage(context, record)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND).apply { type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    context.startActivity(Intent.createChooser(intent, "Compartir partido"))
}

private fun saveMatchImage(context: Context, record: PhoneRecord) {
    val file = createMatchImage(context, record)
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "Punto-Padel-${record.endedAt}.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Punto Padel")
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    if (uri != null) {
        context.contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
        Toast.makeText(context, "Imagen guardada en Galeria", Toast.LENGTH_SHORT).show()
    } else Toast.makeText(context, "No se pudo guardar la imagen", Toast.LENGTH_SHORT).show()
}
private fun formatDate(ms: Long): String = SimpleDateFormat("dd/MM/yyyy · HH:mm", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("America/Argentina/Buenos_Aires") }.format(Date(ms))
