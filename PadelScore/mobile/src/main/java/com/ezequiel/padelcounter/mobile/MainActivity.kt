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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ezequiel.padelcounter.MatchConfig
import com.ezequiel.padelcounter.MatchEngine
import com.ezequiel.padelcounter.MatchState
import com.ezequiel.padelcounter.Sport
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
        val current = loadCurrent()
        val history = loadHistory()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent, onPrimary = Color(0xFF07151B), background = Canvas,
                    surface = Panel, onSurface = Ink, outline = Divider, error = Coral
                )
            ) { PhoneApp(current, history, loadPlayers(history), loadHiddenSports(), openLatest, ::persist, ::savePlayers, ::saveHiddenSports) }
        }
    }
    override fun onStart() { super.onStart(); registerReceiver(receiver, IntentFilter(ACTION_SYNCED), RECEIVER_NOT_EXPORTED) }
    override fun onStop() { unregisterReceiver(receiver); super.onStop() }

    private fun loadCurrent() = runCatching { prefs.getString("current", null)?.let { PhoneCodec.matchFromJson(org.json.JSONObject(it)) } }.getOrNull()
    private fun loadHistory() = runCatching { PhoneCodec.historyFromJson(prefs.getString("history", "[]") ?: "[]") }.getOrDefault(emptyList())
    private fun loadPlayers(history: List<PhoneRecord>): Map<String, List<String>> = runCatching {
        val raw = prefs.getString("players_by_sport", null)
        if (raw != null) {
            val json = org.json.JSONObject(raw)
            listOf("Padel", "Tenis", "Futbol").associateWith { key ->
                val array = json.optJSONArray(key) ?: org.json.JSONArray()
                (0 until array.length()).map { array.getString(it) }
            }
        } else {
            val legacy = org.json.JSONArray(prefs.getString("players", "[]"))
            val oldNames = (0 until legacy.length()).map { legacy.getString(it) }
            val grouped = history.groupBy { it.match.config.sport.familyName }.mapValues { (_, records) ->
                records.flatMap { it.match.config.teamAPlayers + it.match.config.teamBPlayers }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
            }.toMutableMap()
            val assigned = grouped.values.flatten()
            grouped["Futbol"] = (grouped["Futbol"].orEmpty() + oldNames.filter { old -> assigned.none { it.equals(old, true) } }).distinctBy { it.lowercase() }
            grouped
        }
    }.getOrDefault(emptyMap())
    private fun savePlayers(players: Map<String, List<String>>) {
        val json = org.json.JSONObject()
        players.forEach { (sport, names) -> json.put(sport, org.json.JSONArray(names.distinctBy { it.lowercase() }.sorted())) }
        prefs.edit().putString("players_by_sport", json.toString()).apply()
    }
    private fun loadHiddenSports(): Set<String> = prefs.getStringSet("hidden_sports", emptySet()) ?: emptySet()
    private fun saveHiddenSports(hidden: Set<String>) { prefs.edit().putStringSet("hidden_sports", hidden).apply() }
    private fun persist(current: PhoneMatch?, history: List<PhoneRecord>) {
        prefs.edit().apply {
            if (current == null) remove("current") else putString("current", PhoneCodec.matchToJson(current).toString())
            putString("history", PhoneCodec.historyToJson(history))
        }.apply()
        MobileSync.publish(this)
    }

    private fun migratePalette() {
        if (prefs.getBoolean("fixed_colors_v3", false)) return
        fun migrate(json: org.json.JSONObject) { json.put("colorA", 0); json.put("colorB", 1) }
        prefs.edit().apply {
            prefs.getString("current", null)?.let { runCatching { org.json.JSONObject(it).also(::migrate).toString() }.getOrNull()?.let { value -> putString("current", value) } }
            prefs.getString("history", null)?.let { raw -> runCatching { org.json.JSONArray(raw).also { array -> (0 until array.length()).forEach { migrate(array.getJSONObject(it)) } }.toString() }.getOrNull()?.let { value -> putString("history", value) } }
            putBoolean("palette_v2", true)
            putBoolean("fixed_colors_v3", true)
        }.apply()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneApp(initial: PhoneMatch?, initialHistory: List<PhoneRecord>, initialPlayers: Map<String, List<String>>, initialHiddenSports: Set<String>, openLatest: Boolean, persist: (PhoneMatch?, List<PhoneRecord>) -> Unit, savePlayers: (Map<String, List<String>>) -> Unit, saveHiddenSports: (Set<String>) -> Unit) {
    var current by remember { mutableStateOf(initial) }
    var config by remember { mutableStateOf(initial?.config ?: if (openLatest) initialHistory.firstOrNull()?.match?.config ?: MatchConfig() else MatchConfig()) }
    val records = remember { mutableStateListOf<PhoneRecord>().apply { addAll(initialHistory) } }
    val catalogs = remember {
        mutableStateMapOf<String, androidx.compose.runtime.snapshots.SnapshotStateList<String>>().apply {
            listOf("Padel", "Tenis", "Futbol").forEach { key ->
                val fromHistory = initialHistory.filter { it.match.config.sport.familyName == key }
                    .flatMap { it.match.config.teamAPlayers + it.match.config.teamBPlayers }
                put(key, mutableStateListOf<String>().apply {
                    addAll((initialPlayers[key].orEmpty() + fromHistory).filter { it.isNotBlank() }.distinctBy { it.lowercase() })
                })
            }
        }
    }
    val players = catalogs.getValue(config.sport.familyName)
    fun saveCatalogs() = savePlayers(catalogs.mapValues { it.value.toList() })
    fun rememberPlayer(name: String) {
        val clean = formatTeamNameInput(name).trim()
        if (clean.isNotBlank() && players.none { it.equals(clean, true) }) { players.add(clean); saveCatalogs() }
    }
    var tab by remember { mutableIntStateOf(if (openLatest && initialHistory.isNotEmpty()) 2 else if (initial == null) 0 else 1) }
    var detailIndex by remember { mutableStateOf<Int?>(if (openLatest && initialHistory.isNotEmpty()) 0 else null) }
    var showGlobal by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var showSportSettings by remember { mutableStateOf(false) }
    val hiddenSports = remember { mutableStateListOf<String>().apply { addAll(initialHiddenSports) } }
    val visibleSports = listOf(Sport.PADEL, Sport.TENNIS, Sport.FOOTBALL_5).filterNot { it.familyName in hiddenSports }.ifEmpty { listOf(Sport.PADEL) }
    fun selectSport(sport: Sport) {
        config = normalizeRoster(MatchConfig(sport = sport, colorA = 0, colorB = 1))
        detailIndex = null; showGlobal = false; showManual = false
    }
    fun swipeSport(direction: Int) {
        if (current != null || visibleSports.size < 2) return
        val currentIndex = visibleSports.indexOfFirst { if (it.isFootball) config.sport.isFootball else it == config.sport }.coerceAtLeast(0)
        selectSport(visibleSports[(currentIndex + direction + visibleSports.size) % visibleSports.size])
    }
    LaunchedEffect(Unit) {
        if (current == null && config.sport.familyName in hiddenSports) selectSport(visibleSports.first())
    }
    val undo = remember { mutableStateListOf<MatchState>() }

    Scaffold(
        containerColor = Canvas,
        topBar = {
            Column(Modifier.background(Panel)) {
                TopAppBar(
                    title = { Text("Tanteo", color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp) },
                    actions = {
                        IconButton({ showSportSettings = true }, enabled = current == null) { Icon(Icons.Default.Settings, "Mostrar u ocultar deportes", tint = Muted) }
                        Surface(color = if (current == null) Divider else Color(0xFF153E42), shape = RoundedCornerShape(20.dp), modifier = Modifier.padding(end = 16.dp)) {
                            Text(if (current == null) "Sin partido" else "En juego", color = if (current == null) Muted else Color(0xFF65D6C0), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                        }
                    }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Panel)
                )
                SportNavigation(config.sport, visibleSports, current == null, ::selectSport)
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Panel, tonalElevation = 8.dp) {
                NavigationBarItem(tab == 0, { tab = 0; detailIndex = null; showGlobal = false; showManual = false }, icon = { Icon(Icons.Default.AddCircle, null) }, label = { Text("Nuevo") }, colors = navColors())
                NavigationBarItem(tab == 1, { tab = 1; detailIndex = null; showGlobal = false; showManual = false }, icon = { Icon(Icons.Default.Scoreboard, null) }, label = { Text("Tanteador") }, colors = navColors())
                NavigationBarItem(tab == 2, { tab = 2; detailIndex = null; showGlobal = false; showManual = false }, icon = { Icon(Icons.Default.History, null) }, label = { Text("Historial") }, colors = navColors())
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(Canvas).pointerInput(current, visibleSports) {
            var drag = 0f
            detectHorizontalDragGestures(
                onDragStart = { drag = 0f },
                onHorizontalDrag = { _, amount -> drag += amount },
                onDragEnd = { if (drag < -80f) swipeSport(1) else if (drag > 80f) swipeSport(-1) }
            )
        }) {
            when {
                tab == 0 -> SetupPhone(config, { config = it }, players, ::rememberPlayer) {
                    val fixed = normalizeRoster(config.copy(teamA = "Mi Equipo", teamB = "Rival", colorA = 0, colorB = 1))
                    (fixed.teamAPlayers + fixed.teamBPlayers).forEach(::rememberPlayer)
                    config = fixed; current = PhoneMatch(fixed, MatchState()); undo.clear(); persist(current, records); tab = 1
                }
                tab == 1 -> current?.let { match ->
                    val addScore: (Boolean) -> Unit = { teamA ->
                        undo.add(match.state)
                        val next = if (match.config.sport.isFootball) match.state.copy(
                            pointsA = match.state.pointsA + if (teamA) 1 else 0,
                            pointsB = match.state.pointsB + if (teamA) 0 else 1,
                            goalEvents = match.state.goalEvents + com.ezequiel.padelcounter.GoalEvent(
                                teamA,
                                (System.currentTimeMillis() - match.state.startedAt).coerceAtLeast(0)
                            )
                        ) else MatchEngine.point(match.state, teamA, match.config)
                        current = match.copy(state = next); persist(current, records)
                    }
                    val finishMatch = {
                        records.add(0, PhoneRecord(System.currentTimeMillis(), match.copy(state = match.state.copy(finished = true))))
                        current = null; config = MatchConfig(sport = match.config.sport); undo.clear(); persist(null, records); tab = 2; detailIndex = 0
                    }
                    if (match.config.sport.isFootball) FootballScorePhone(match, undo.isNotEmpty(), addScore,
                        undo = { if (undo.isNotEmpty()) { current = match.copy(state = undo.removeAt(undo.lastIndex)); persist(current, records) } }, finish = finishMatch)
                    else ScorePhone(match, undo.isNotEmpty(),
                        point = addScore,
                        undo = { if (undo.isNotEmpty()) { current = match.copy(state = undo.removeAt(undo.lastIndex)); persist(current, records) } },
                        finish = finishMatch)
                } ?: EmptyScore { tab = 0 }
                showManual -> ManualMatchScreen(config.sport, players, ::rememberPlayer, { showManual = false }) { record ->
                    records.add(record); records.sortByDescending { it.endedAt }; persist(current, records); showManual = false
                    detailIndex = records.indexOfFirst { it.endedAt == record.endedAt }
                }
                detailIndex != null -> DetailPhone(records[detailIndex!!], players, ::rememberPlayer,
                    update = {
                        records[detailIndex!!] = it
                        records.sortByDescending { record -> record.endedAt }
                        detailIndex = records.indexOf(it)
                        persist(current, records)
                    },
                    delete = { records.removeAt(detailIndex!!); persist(current, records); detailIndex = null },
                    back = { detailIndex = null })
                showGlobal -> GlobalStatsPhone(
                    records.filter { if (config.sport.isFootball) it.match.config.sport.isFootball else it.match.config.sport == config.sport },
                    config.sport,
                    players,
                    deletePlayer = { name ->
                        players.removeAll { it.equals(name, true) }; saveCatalogs()
                        records.indices.forEach { index ->
                            val match = records[index].match; val c = match.config
                            if (c.sport.familyName != config.sport.familyName) return@forEach
                            val userDeleted = c.userPlayer.equals(name, true)
                            val updated = c.copy(
                                userPlayer = if (userDeleted) "Yo" else c.userPlayer,
                                teamAPlayers = c.teamAPlayers.mapIndexed { playerIndex, player -> if (player.equals(name, true)) { if (userDeleted && playerIndex == 0) "Yo" else "" } else player },
                                teamBPlayers = c.teamBPlayers.map { if (it.equals(name, true)) "" else it }
                            )
                            records[index] = records[index].copy(match = match.copy(config = updated))
                        }
                        current = current?.let { match ->
                            val c = match.config
                            if (c.sport.familyName != config.sport.familyName) return@let match
                            val userDeleted = c.userPlayer.equals(name, true)
                            match.copy(config = c.copy(
                                userPlayer = if (userDeleted) "Yo" else c.userPlayer,
                                teamAPlayers = c.teamAPlayers.mapIndexed { index, player ->
                                    if (!player.equals(name, true)) player else if (userDeleted && index == 0) "Yo" else ""
                                },
                                teamBPlayers = c.teamBPlayers.map { if (it.equals(name, true)) "" else it }
                            ))
                        }
                        persist(current, records)
                    }, back = { showGlobal = false })
                else -> HistoryPhone(records, config.sport, { detailIndex = it }, { showGlobal = true }) { showManual = true }
            }
        }
    }
    if (showSportSettings) SportVisibilityDialog(hiddenSports, {
        hiddenSports.clear(); hiddenSports.addAll(it); saveHiddenSports(it)
        if (config.sport.familyName in it) selectSport(listOf(Sport.PADEL, Sport.TENNIS, Sport.FOOTBALL_5).first { sport -> sport.familyName !in it })
        showSportSettings = false
    }) { showSportSettings = false }
}

@Composable
private fun navColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = Color.White, selectedTextColor = Accent, indicatorColor = Accent,
    unselectedIconColor = Muted, unselectedTextColor = Muted
)

@Composable
private fun SportNavigation(selected: Sport, sports: List<Sport>, enabled: Boolean, update: (Sport) -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 18.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        sports.forEach { sport ->
            SportTab(sport.familyName, if (sport.isFootball) selected.isFootball else selected == sport, enabled) { update(if (sport.isFootball && selected.isFootball) selected else sport) }
        }
    }
}

@Composable
private fun RowScope.SportTab(label: String, selected: Boolean, enabled: Boolean, click: () -> Unit) {
    Column(Modifier.weight(1f).fillMaxHeight().clickable(enabled = enabled, onClick = click), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(label, color = if (selected) Ink else Muted, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
        Spacer(Modifier.height(8.dp)); Box(Modifier.fillMaxWidth(.62f).height(3.dp).background(if (selected) Accent else Color.Transparent, CircleShape))
    }
}

@Composable
private fun SportVisibilityDialog(hidden: List<String>, save: (Set<String>) -> Unit, dismiss: () -> Unit) {
    val selected = remember(hidden) { mutableStateListOf<String>().apply { addAll(hidden) } }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Deportes visibles") },
        text = {
            Column {
                listOf("Padel", "Tenis", "Futbol").forEach { sport ->
                    val visible = sport !in selected
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !visible || selected.size < 2) {
                            if (visible) selected.add(sport) else selected.remove(sport)
                        }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(visible, {
                            if (it) selected.remove(sport) else if (selected.size < 2) selected.add(sport)
                        })
                        Spacer(Modifier.width(8.dp)); Text(sport)
                    }
                }
                Text("El historial no se elimina al ocultar un deporte.", color = Muted, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton({ save(selected.toSet()) }) { Text("Guardar") } },
        dismissButton = { TextButton(dismiss) { Text("Cancelar") } }
    )
}

private fun normalizeRoster(config: MatchConfig): MatchConfig {
    val size = if (config.sport.isFootball) config.sport.teamSize else if (config.doubles) 2 else 1
    val user = config.userPlayer.ifBlank { "Yo" }
    val teamA = MutableList(size) { index -> config.teamAPlayers.getOrNull(index).orEmpty() }
    val teamB = MutableList(size) { index -> config.teamBPlayers.getOrNull(index).orEmpty() }
    teamA[0] = user
    return config.copy(teamA = "Mi Equipo", teamB = "Rival", userPlayer = user, teamAPlayers = teamA, teamBPlayers = teamB)
}

private fun matchupLabel(config: MatchConfig): String {
    if (config.sport.isFootball) return "Mi Equipo vs Rival"
    val own = config.teamAPlayers.filter { it.isNotBlank() }.ifEmpty { listOf(config.userPlayer.ifBlank { "Yo" }) }
    val rivals = config.teamBPlayers.filter { it.isNotBlank() }.ifEmpty { listOf("Rival") }
    return "${own.joinToString(" ")} vs ${rivals.joinToString(" ")}"
}

private fun sideLabel(config: MatchConfig, own: Boolean): String {
    if (config.sport.isFootball) return if (own) "Mi Equipo" else "Rival"
    val names = (if (own) config.teamAPlayers else config.teamBPlayers).filter { it.isNotBlank() }
    return names.ifEmpty { listOf(if (own) config.userPlayer.ifBlank { "Yo" } else "Rival") }.joinToString(" ")
}

@Composable
private fun SetupPhone(config: MatchConfig, update: (MatchConfig) -> Unit, players: List<String>, rememberPlayer: (String) -> Unit, start: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 92.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Column { Text("NUEVO PARTIDO", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold); Text("Configura el partido", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold) } }
            if (config.sport.isFootball) item { OptionRow("Modalidad", listOf("Futbol 5", "Futbol 7", "Futbol 11"), listOf(Sport.FOOTBALL_5, Sport.FOOTBALL_7, Sport.FOOTBALL_11).indexOf(config.sport)) { update(normalizeRoster(config.copy(sport = listOf(Sport.FOOTBALL_5, Sport.FOOTBALL_7, Sport.FOOTBALL_11)[it]))) } }
            if (!config.sport.isFootball) {
                item { OptionRow("Sets", listOf("1", "3", "5", "Libre"), listOf(1, 3, 5, 0).indexOf(config.maxSets)) { update(config.copy(maxSets = listOf(1, 3, 5, 0)[it])) } }
                item { OptionRow("Primer saque", listOf("Mi Equipo", "Rival"), if (config.initialServerA) 0 else 1) { update(config.copy(initialServerA = it == 0)) } }
                item { OptionRow("Ventaja", listOf("Si", "No"), if (config.advantage) 0 else 1) { update(config.copy(advantage = it == 0)) } }
                item { OptionRow("Modalidad", listOf("1 vs 1", "2 vs 2"), if (config.doubles) 1 else 0) { update(normalizeRoster(config.copy(doubles = it == 1))) } }
            }
            item { RosterEditor(normalizeRoster(config), players, update, rememberPlayer) }
        }
        Surface(Modifier.fillMaxWidth().align(Alignment.BottomCenter), color = Canvas, shadowElevation = 8.dp) {
            Button(start, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).height(52.dp), shape = RoundedCornerShape(8.dp)) { Icon(Icons.Default.Scoreboard, null); Spacer(Modifier.width(8.dp)); Text("Empezar partido", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun RosterEditor(config: MatchConfig, catalog: List<String>, update: (MatchConfig) -> Unit, rememberPlayer: (String) -> Unit) {
    val selected = config.teamAPlayers + config.teamBPlayers
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Integrantes", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("${config.sport.displayName} · ${config.teamAPlayers.size} ${if (config.teamAPlayers.size == 1) "jugador" else "jugadores"} por lado", color = Muted, fontSize = 12.sp)
        Text("Mi Equipo", color = Coral, fontWeight = FontWeight.Bold)
        config.teamAPlayers.forEachIndexed { index, name ->
            PlayerSelector(if (index == 0) "Vos" else "Compañero ${index + 1}", name, catalog, selected, rememberPlayer) { value ->
                val list = config.teamAPlayers.toMutableList().apply { this[index] = value }
                val next = if (index == 0) config.copy(userPlayer = value.ifBlank { "Yo" }, teamAPlayers = list) else config.copy(teamAPlayers = list)
                update(next)
            }
        }
        Text("Rival", color = Palette[1], fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        config.teamBPlayers.forEachIndexed { index, name ->
            PlayerSelector("Rival ${index + 1}", name, catalog, selected, rememberPlayer) { value ->
                update(config.copy(teamBPlayers = config.teamBPlayers.toMutableList().apply { this[index] = value }))
            }
        }
    }
}

@Composable
private fun PlayerSelector(label: String, value: String, catalog: List<String>, selected: List<String>, commit: (String) -> Unit, update: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val suggestions = catalog.filter { candidate -> candidate.contains(value, true) && (candidate.equals(value, true) || selected.none { it.equals(candidate, true) }) }.take(6)
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value, { update(formatTeamNameInput(it)) }, label = { Text(label) }, singleLine = true,
            shape = RoundedCornerShape(7.dp), modifier = Modifier.fillMaxWidth(),
            trailingIcon = { if (suggestions.isNotEmpty()) TextButton({ expanded = !expanded }) { Text("Elegir") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { commit(value) })
        )
        DropdownMenu(expanded && suggestions.isNotEmpty(), { expanded = false }, modifier = Modifier.fillMaxWidth(.82f)) {
            suggestions.forEach { player -> DropdownMenuItem({ Text(player) }, onClick = { update(player); commit(player); expanded = false }) }
        }
    }
}

@Composable
private fun FootballScorePhone(match: PhoneMatch, canUndo: Boolean, goal: (Boolean) -> Unit, undo: () -> Unit, finish: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(match.config.sport.displayName.uppercase(), color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold); Text(formatDuration((now - match.state.startedAt).coerceAtLeast(0)), color = Ink, fontSize = 40.sp, fontWeight = FontWeight.Black) }
            IconButton(undo, enabled = canUndo, modifier = Modifier.background(Panel, CircleShape)) { Icon(Icons.Default.Undo, "Deshacer", tint = if (canUndo) Ink else Muted) }
            Spacer(Modifier.width(8.dp)); OutlinedButton(finish, shape = RoundedCornerShape(8.dp)) { Icon(Icons.Default.StopCircle, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Finalizar") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FootballTeam("Mi Equipo", Palette[match.config.colorA], match.state.pointsA, { goal(true) }, Modifier.weight(1f))
            FootballTeam("Rival", Palette[match.config.colorB], match.state.pointsB, { goal(false) }, Modifier.weight(1f))
        }
        Text("Toca un equipo para sumar un gol", color = Muted, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
private fun FootballTeam(name: String, color: Color, goals: Int, goal: () -> Unit, modifier: Modifier) {
    Column(modifier.height(300.dp).clip(RoundedCornerShape(8.dp)).background(Panel).clickable(onClick = goal).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(color)); Spacer(Modifier.height(24.dp))
        Text(name, color = color, fontSize = 19.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.weight(1f)); Text(goals.toString(), color = Ink, fontSize = 76.sp, fontWeight = FontWeight.Black); Spacer(Modifier.weight(1f))
        Text("SUMAR GOL", color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
    val name = if (teamA) "Mi Equipo" else "Rival"
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualMatchScreen(sport: Sport, players: List<String>, rememberPlayer: (String) -> Unit, back: () -> Unit, save: (PhoneRecord) -> Unit) {
    var config by remember(sport) { mutableStateOf(normalizeRoster(MatchConfig(sport = sport, colorA = 0, colorB = 1))) }
    var scoreA by remember { mutableStateOf("0") }; var scoreB by remember { mutableStateOf("0") }
    var durationMinutes by remember { mutableStateOf("90") }
    var endedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showDatePicker by remember { mutableStateOf(false) }
    if (showDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = endedAt)
        DatePickerDialog(onDismissRequest = { showDatePicker = false }, confirmButton = {
            TextButton({ pickerState.selectedDateMillis?.let { endedAt = selectedLocalDate(it) }; showDatePicker = false }) { Text("Aceptar") }
        }, dismissButton = { TextButton({ showDatePicker = false }) { Text("Cancelar") } }) { DatePicker(pickerState) }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 92.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { TextButton(back, contentPadding = PaddingValues(0.dp)) { Icon(Icons.Default.ArrowBack, null); Spacer(Modifier.width(6.dp)); Text("Historial") } }
            item { Column { Text("CARGA MANUAL · ${sport.displayName.uppercase()}", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold); Text("Agregar partido anterior", color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold); Text("Los datos de salud quedaran sin medicion.", color = Muted, fontSize = 12.sp) } }
            item { OutlinedButton({ showDatePicker = true }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(7.dp)) { Icon(Icons.Default.CalendarMonth, null); Spacer(Modifier.width(8.dp)); Text(SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(endedAt))) } }
            if (config.sport.isFootball) item { OptionRow("Modalidad", listOf("Futbol 5", "Futbol 7", "Futbol 11"), listOf(Sport.FOOTBALL_5, Sport.FOOTBALL_7, Sport.FOOTBALL_11).indexOf(config.sport)) { config = normalizeRoster(config.copy(sport = listOf(Sport.FOOTBALL_5, Sport.FOOTBALL_7, Sport.FOOTBALL_11)[it])) } }
            if (!config.sport.isFootball) item { OptionRow("Modalidad", listOf("1 vs 1", "2 vs 2"), if (config.doubles) 1 else 0) { config = normalizeRoster(config.copy(doubles = it == 1)) } }
            item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { NumericField(if (sport.isFootball) "Goles propios" else "Sets propios", scoreA, { scoreA = it }, Modifier.weight(1f)); NumericField(if (sport.isFootball) "Goles rival" else "Sets rival", scoreB, { scoreB = it }, Modifier.weight(1f)) } }
            item { NumericField("Duracion total (minutos)", durationMinutes, { durationMinutes = it }, Modifier.fillMaxWidth()) }
            item { RosterEditor(normalizeRoster(config), players, { config = it }, rememberPlayer) }
        }
        Surface(Modifier.fillMaxWidth().align(Alignment.BottomCenter), color = Canvas, shadowElevation = 8.dp) {
            Button({
                val finalConfig = normalizeRoster(config.copy(teamA = "Mi Equipo", teamB = "Rival", colorA = 0, colorB = 1))
                (finalConfig.teamAPlayers + finalConfig.teamBPlayers).forEach(rememberPlayer)
                val duration = (durationMinutes.toLongOrNull() ?: 0L).coerceAtLeast(0) * 60_000L
                val startedAt = (endedAt - duration).coerceAtLeast(0)
                val a = scoreA.toIntOrNull()?.coerceAtLeast(0) ?: 0; val b = scoreB.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val state = MatchState(pointsA = if (sport.isFootball) a else 0, pointsB = if (sport.isFootball) b else 0, setsA = if (sport.isFootball) 0 else a, setsB = if (sport.isFootball) 0 else b, finished = true, startedAt = startedAt, setStartedAt = startedAt, gameStartedAt = startedAt)
                save(PhoneRecord(endedAt, PhoneMatch(finalConfig, state), manuallyEntered = true))
            }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).height(52.dp), shape = RoundedCornerShape(8.dp)) { Text("Guardar partido", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun NumericField(label: String, value: String, update: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(value, { update(it.filter(Char::isDigit).take(4)) }, label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = modifier)
}

private fun selectedLocalDate(utcDate: Long, preserveTimeFrom: Long? = null): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcDate }
    val previous = preserveTimeFrom?.let { Calendar.getInstance().apply { timeInMillis = it } }
    return Calendar.getInstance().apply {
        set(Calendar.YEAR, utc.get(Calendar.YEAR)); set(Calendar.MONTH, utc.get(Calendar.MONTH)); set(Calendar.DAY_OF_MONTH, utc.get(Calendar.DAY_OF_MONTH))
        set(Calendar.HOUR_OF_DAY, previous?.get(Calendar.HOUR_OF_DAY) ?: 12)
        set(Calendar.MINUTE, previous?.get(Calendar.MINUTE) ?: 0)
        set(Calendar.SECOND, previous?.get(Calendar.SECOND) ?: 0)
        set(Calendar.MILLISECOND, previous?.get(Calendar.MILLISECOND) ?: 0)
    }.timeInMillis
}

@Composable
private fun HistoryPhone(records: List<PhoneRecord>, sport: Sport, open: (Int) -> Unit, global: () -> Unit, manual: () -> Unit) {
    val filtered = records.withIndex().filter { if (sport.isFootball) it.value.match.config.sport.isFootball else it.value.match.config.sport == sport }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) { Column(Modifier.weight(1f)) { Text("HISTORIAL · ${sport.familyName.uppercase()}", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold); Text("Partidos jugados", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold) } }; Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilledTonalButton(global, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 12.dp)) { Icon(Icons.Default.Analytics, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Resumen") }; OutlinedButton(manual, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 12.dp)) { Icon(Icons.Default.AddCircle, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Cargar partido") } } } }
        if (filtered.isEmpty()) item { Text("Todavia no hay partidos finalizados.", color = Muted) }
        itemsIndexed(filtered) { _, indexed ->
            val index = indexed.index; val record = indexed.value
            val m = record.match
            val scoreA = if (sport.isFootball) m.state.pointsA else m.state.setsA; val scoreB = if (sport.isFootball) m.state.pointsB else m.state.setsB
            val outcome = when { scoreA > scoreB -> Win; scoreA < scoreB -> Loss; else -> Draw }
            Row(Modifier.fillMaxWidth().height(76.dp).clip(RoundedCornerShape(7.dp)).background(Panel).clickable { open(index) }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(6.dp)).background(outcome.copy(alpha = .18f)), contentAlignment = Alignment.Center) { Text("$scoreA-$scoreB", color = outcome, fontSize = 17.sp, fontWeight = FontWeight.Black) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) { Text(matchupLabel(m.config), color = Ink, fontWeight = FontWeight.Bold, maxLines = 1); Text(formatDate(record.endedAt), color = Muted, fontSize = 12.sp) }
                Text("Ver", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun GlobalStatsPhone(records: List<PhoneRecord>, sport: Sport, catalog: List<String>, deletePlayer: (String) -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    fun scores(record: PhoneRecord) = if (record.match.config.sport.isFootball) record.match.state.pointsA to record.match.state.pointsB else record.match.state.setsA to record.match.state.setsB
    val wins = records.count { scores(it).let { (a, b) -> a > b } }; val losses = records.count { scores(it).let { (a, b) -> a < b } }; val draws = records.size - wins - losses
    val setsWon = records.sumOf { it.match.state.setsA }; val setsLost = records.sumOf { it.match.state.setsB }
    val games = records.sumOf { it.match.state.completedGames }; val total = records.sumOf { (it.endedAt - it.match.state.startedAt).coerceAtLeast(0) }
    val healthRecords = records.map { it.match.state }.filter { it.averageHeartRate > 0 || it.distanceMeters > 0 || it.calories > 0 }
    val avgHeart = healthRecords.map { it.averageHeartRate }.filter { it > 0 }.average().takeIf { !it.isNaN() } ?: 0.0
    val maxHeart = healthRecords.maxOfOrNull { it.maxHeartRate } ?: 0.0; val distance = healthRecords.sumOf { it.distanceMeters }; val calories = healthRecords.sumOf { it.calories }; val steps = healthRecords.sumOf { it.steps }
    val isFootball = sport.isFootball
    val teammateStats = playerSummaries(records, teammates = true)
    val rivalStats = playerSummaries(records, teammates = false)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { TextButton(back) { Icon(Icons.Default.ArrowBack, null); Spacer(Modifier.width(6.dp)); Text("Historial") }; Spacer(Modifier.weight(1f)); FilledTonalIconButton({ saveGlobalStatsImage(context, records, sport) }) { Icon(Icons.Default.Download, "Guardar resumen") }; Spacer(Modifier.width(8.dp)); FilledTonalIconButton({ shareGlobalStatsImage(context, records, sport) }) { Icon(Icons.Default.Share, "Compartir resumen") } } }
        item { Text("Estadisticas globales", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold) }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { GlobalResult("Ganados", wins, Win, Modifier.weight(1f)); GlobalResult("Empates", draws, Draw, Modifier.weight(1f)); GlobalResult("Perdidos", losses, Loss, Modifier.weight(1f)) } }
        item { Surface(Modifier.fillMaxWidth(), color = Panel, shape = RoundedCornerShape(8.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Divider)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { GlobalLine("Partidos jugados", records.size.toString()); if (records.firstOrNull()?.match?.config?.sport?.isFootball == true) { GlobalLine("Goles a favor", records.sumOf { it.match.state.pointsA }.toString()); GlobalLine("Goles en contra", records.sumOf { it.match.state.pointsB }.toString()) } else { GlobalLine("Sets", "$setsWon - $setsLost"); GlobalLine("Games completados", games.toString()) }; GlobalLine("Tiempo en cancha", formatDuration(total)); GlobalLine("Promedio por partido", formatDuration(if (records.isEmpty()) 0 else total / records.size)) } } }
        if (teammateStats.isNotEmpty()) item { PlayerStatsSection("Con mis compañeros", teammateStats, Coral) }
        if (rivalStats.isNotEmpty()) item { PlayerStatsSection("Contra rivales", rivalStats, Palette[1]) }
        if (catalog.isNotEmpty()) item { PlayerCatalogSection(catalog, deletePlayer) }
        if (healthRecords.isNotEmpty()) item { Surface(Modifier.fillMaxWidth(), color = Panel, shape = RoundedCornerShape(8.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Divider)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { Text("Actividad acumulada", color = Ink, fontWeight = FontWeight.Bold); GlobalLine("Ritmo promedio", "${avgHeart.toInt()} ppm"); GlobalLine("Ritmo maximo", "${maxHeart.toInt()} ppm"); GlobalLine("Distancia", "%.2f km".format(distance / 1000)); GlobalLine("Pasos", steps.toString()); GlobalLine("Calorias", "${calories.toInt()} kcal") } } }
        if (healthRecords.isNotEmpty()) item { HealthProgressCharts(records) }
    }
}

@Composable
private fun PlayerCatalogSection(players: List<String>, deletePlayer: (String) -> Unit) {
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    pendingDelete?.let { player ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Eliminar integrante") },
            text = { Text("¿Eliminar a $player de este deporte y de sus partidos anteriores?") },
            confirmButton = { TextButton({ deletePlayer(player); pendingDelete = null }) { Text("Eliminar", color = Loss) } },
            dismissButton = { TextButton({ pendingDelete = null }) { Text("Cancelar") } }
        )
    }
    Surface(Modifier.fillMaxWidth(), color = Panel, shape = RoundedCornerShape(8.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Divider)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Jugadores guardados", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("Eliminar tambien los quita de partidos y estadisticas anteriores.", color = Muted, fontSize = 11.sp)
            players.sorted().forEach { player ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(player, color = Ink, modifier = Modifier.weight(1f))
                    IconButton({ pendingDelete = player }) { Icon(Icons.Default.PersonRemove, "Eliminar $player", tint = Loss) }
                }
            }
        }
    }
}

private data class PlayerSummary(val name: String, val matches: Int, val wins: Int, val draws: Int, val losses: Int)

private fun playerSummaries(records: List<PhoneRecord>, teammates: Boolean): List<PlayerSummary> {
    data class MutableSummary(var matches: Int = 0, var wins: Int = 0, var draws: Int = 0, var losses: Int = 0)
    val result = linkedMapOf<String, Pair<String, MutableSummary>>()
    records.forEach { record ->
        val config = record.match.config; val state = record.match.state
        val user = config.userPlayer.trim()
        val names = if (teammates) config.teamAPlayers.filterNot { it.equals(user, true) } else config.teamBPlayers
        names.filter { it.isNotBlank() }.distinctBy { it.lowercase() }.forEach { name ->
            val key = name.lowercase(); val entry = result.getOrPut(key) { name to MutableSummary() }.second
            entry.matches++
            val scoreA = if (config.sport.isFootball) state.pointsA else state.setsA
            val scoreB = if (config.sport.isFootball) state.pointsB else state.setsB
            when { scoreA > scoreB -> entry.wins++; scoreA < scoreB -> entry.losses++; else -> entry.draws++ }
        }
    }
    return result.values.map { (name, value) -> PlayerSummary(name, value.matches, value.wins, value.draws, value.losses) }.sortedByDescending { it.matches }
}

@Composable
private fun PlayerStatsSection(title: String, players: List<PlayerSummary>, accent: Color) {
    Surface(Modifier.fillMaxWidth(), color = Panel, shape = RoundedCornerShape(8.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Divider)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().background(accent.copy(alpha = .10f)).padding(horizontal = 8.dp, vertical = 7.dp)) {
                Text("JUGADOR", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(2.2f))
                listOf("PJ", "G", "E", "P").forEach { label -> Text(label, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.weight(.65f)) }
            }
            players.take(12).forEach { player ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(player.name, color = Ink, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(2.2f))
                    Text(player.matches.toString(), color = Ink, textAlign = TextAlign.Center, modifier = Modifier.weight(.65f))
                    Text(player.wins.toString(), color = Win, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.weight(.65f))
                    Text(player.draws.toString(), color = Draw, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.weight(.65f))
                    Text(player.losses.toString(), color = Loss, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.weight(.65f))
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(accent.copy(alpha = .14f)))
            }
        }
    }
}

@Composable
private fun HealthProgressCharts(records: List<PhoneRecord>) {
    val ordered = records.sortedBy { it.endedAt }
    val heart = ordered.filter { it.match.state.averageHeartRate > 0 }.map { it.endedAt to it.match.state.averageHeartRate }
    val distance = ordered.filter { it.match.state.distanceMeters > 0 }.map { it.endedAt to it.match.state.distanceMeters / 1000.0 }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (heart.isNotEmpty()) ProgressChart("Evolucion del ritmo cardiaco", heart, "ppm", Accent)
        if (distance.isNotEmpty()) ProgressChart("Evolucion de la distancia", distance, "km", Win)
    }
}

@Composable
private fun ProgressChart(title: String, values: List<Pair<Long, Double>>, unit: String, color: Color) {
    val min = values.minOf { it.second }; val max = values.maxOf { it.second }; val range = (max - min).takeIf { it > 0 } ?: 1.0
    Surface(Modifier.fillMaxWidth(), color = Panel, shape = RoundedCornerShape(8.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Divider)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = Ink, fontWeight = FontWeight.Bold)
            Text("Ultimo: ${if (unit == "km") "%.2f".format(values.last().second) else values.last().second.toInt()} $unit", color = color, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(130.dp).padding(top = 14.dp)) {
                val left = 6.dp.toPx(); val right = size.width - 6.dp.toPx(); val top = 6.dp.toPx(); val bottom = size.height - 10.dp.toPx()
                drawLine(Divider, Offset(left, bottom), Offset(right, bottom), strokeWidth = 1.dp.toPx())
                drawLine(Divider, Offset(left, top), Offset(left, bottom), strokeWidth = 1.dp.toPx())
                val points = values.mapIndexed { index, pair ->
                    val x = if (values.size == 1) (left + right) / 2 else left + (right - left) * index / (values.size - 1)
                    val y = bottom - ((pair.second - min) / range).toFloat() * (bottom - top)
                    Offset(x, y)
                }
                points.zipWithNext().forEach { (a, b) -> drawLine(color, a, b, strokeWidth = 3.dp.toPx()) }
                points.forEach { drawCircle(color, radius = 4.dp.toPx(), center = it) }
            }
            Row(Modifier.fillMaxWidth()) {
                Text(SimpleDateFormat("dd/MM", Locale.getDefault()).format(Date(values.first().first)), color = Muted, fontSize = 10.sp)
                Spacer(Modifier.weight(1f)); Text(SimpleDateFormat("dd/MM", Locale.getDefault()).format(Date(values.last().first)), color = Muted, fontSize = 10.sp)
            }
        }
    }
}

@Composable private fun GlobalResult(label: String, value: Int, color: Color, modifier: Modifier) { Column(modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = .14f)).padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(value.toString(), color = color, fontSize = 28.sp, fontWeight = FontWeight.Black); Text(label, color = Ink, fontSize = 12.sp) } }
@Composable private fun GlobalLine(label: String, value: String) { Row(Modifier.fillMaxWidth()) { Text(label, color = Muted, modifier = Modifier.weight(1f)); Text(value, color = Ink, fontWeight = FontWeight.Bold) } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailPhone(record: PhoneRecord, players: List<String>, rememberPlayer: (String) -> Unit, update: (PhoneRecord) -> Unit, delete: () -> Unit, back: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    var editDate by remember(record.endedAt) { mutableStateOf(false) }
    val m = record.match; val context = LocalContext.current
    if (editDate) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = record.endedAt)
        DatePickerDialog(
            onDismissRequest = { editDate = false },
            confirmButton = {
                TextButton({
                    pickerState.selectedDateMillis?.let { selected ->
                        val changedEndedAt = selectedLocalDate(selected, record.endedAt)
                        val delta = changedEndedAt - record.endedAt
                        val shifted = m.state.copy(
                            startedAt = m.state.startedAt + delta,
                            setStartedAt = m.state.setStartedAt + delta,
                            gameStartedAt = m.state.gameStartedAt + delta
                        )
                        update(record.copy(endedAt = changedEndedAt, match = m.copy(state = shifted)))
                    }
                    editDate = false
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton({ editDate = false }) { Text("Cancelar") } }
        ) { DatePicker(pickerState) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { TextButton(back) { Icon(Icons.Default.ArrowBack, null); Spacer(Modifier.width(6.dp)); Text("Volver") }; Spacer(Modifier.weight(1f)); FilledTonalIconButton(onClick = { saveMatchImage(context, record) }) { Icon(Icons.Default.Download, "Guardar en Galeria") }; Spacer(Modifier.width(8.dp)); FilledTonalIconButton(onClick = { shareMatchImage(context, record) }) { Icon(Icons.Default.Share, "Compartir partido") } } }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(formatDate(record.endedAt), color = Muted, modifier = Modifier.weight(1f))
                OutlinedButton({ editDate = true }, shape = RoundedCornerShape(7.dp)) {
                    Icon(Icons.Default.CalendarMonth, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Cambiar fecha")
                }
            }
        }
        item { Text(matchupLabel(m.config), color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        item { Text(if (m.config.sport.isFootball) "${m.state.pointsA} - ${m.state.pointsB}" else "${m.state.setsA} - ${m.state.setsB}", color = Accent, fontSize = 40.sp, fontWeight = FontWeight.Black) }
        item { MatchStatsTable(record) }
        if (m.config.sport.isFootball && m.state.goalEvents.isNotEmpty()) item { GoalTimelinePhone(m.state.goalEvents) }
        item {
            RosterEditor(normalizeRoster(m.config), players, { changed ->
                update(record.copy(match = m.copy(config = changed)))
            }, rememberPlayer)
        }
        item { HealthStatsPhone(m.state) }
        item { OutlinedButton({ if (confirm) delete() else confirm = true }, shape = RoundedCornerShape(7.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF777D))) { Icon(Icons.Default.Delete, null); Spacer(Modifier.width(7.dp)); Text(if (confirm) "Confirmar eliminacion" else "Eliminar partido") } }
    }
}

@Composable
private fun GoalTimelinePhone(events: List<com.ezequiel.padelcounter.GoalEvent>) {
    var scoreA = 0
    var scoreB = 0
    Surface(Modifier.fillMaxWidth(), color = Panel, shape = RoundedCornerShape(8.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Divider)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Goles", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            events.forEach { goal ->
                if (goal.teamA) scoreA++ else scoreB++
                Row(Modifier.fillMaxWidth()) {
                    Text(if (goal.teamA) "Mi Equipo" else "Rival", color = if (goal.teamA) Coral else Palette[1], modifier = Modifier.weight(1f))
                    Text("$scoreA - $scoreB", color = Ink, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(18.dp))
                    Text(formatGoalTime(goal.elapsedMillis), color = Accent, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun formatGoalTime(elapsedMillis: Long): String {
    val seconds = elapsedMillis.coerceAtLeast(0) / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

@Composable
private fun FootballRosterSummary(config: MatchConfig) {
    Surface(Modifier.fillMaxWidth(), color = Panel, shape = RoundedCornerShape(8.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Divider)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Mi Equipo", color = Coral, fontWeight = FontWeight.Bold)
                config.teamAPlayers.filter { it.isNotBlank() }.forEach { Text(if (it.equals(config.userPlayer, true)) "$it (vos)" else it, color = Ink, fontSize = 12.sp) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Rival", color = Palette[1], fontWeight = FontWeight.Bold)
                config.teamBPlayers.filter { it.isNotBlank() }.forEach { Text(it, color = Ink, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun HealthStatsPhone(state: MatchState) {
    Surface(Modifier.fillMaxWidth(), color = Panel, shape = RoundedCornerShape(8.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Divider)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Actividad", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth()) { HealthMetric("Ritmo promedio", if (state.averageHeartRate > 0) "${state.averageHeartRate.toInt()} ppm" else "—", Modifier.weight(1f)); HealthMetric("Ritmo maximo", if (state.maxHeartRate > 0) "${state.maxHeartRate.toInt()} ppm" else "—", Modifier.weight(1f)) }
            Row(Modifier.fillMaxWidth()) { HealthMetric(if (state.distanceEstimated) "Distancia estimada" else "Distancia", if (state.distanceMeters > 0) "%.2f km".format(state.distanceMeters / 1000) else "—", Modifier.weight(1f)); HealthMetric("Pasos", if (state.steps > 0) state.steps.toString() else "—", Modifier.weight(1f)) }
            HealthMetric("Calorias", if (state.calories > 0) "${state.calories.toInt()} kcal" else "—", Modifier.fillMaxWidth())
        }
    }
}
@Composable private fun HealthMetric(label: String, value: String, modifier: Modifier) { Column(modifier) { Text(label, color = Muted, fontSize = 11.sp); Text(value, color = Accent, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold) } }

@Composable
private fun MatchStatsTable(record: PhoneRecord) {
    val state = record.match.state
    if (record.match.config.sport.isFootball) {
        Surface(Modifier.fillMaxWidth(), color = Panel, shape = RoundedCornerShape(8.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Divider)) {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(record.match.config.sport.displayName, color = Accent, fontWeight = FontWeight.Bold); Text("Duracion del partido", color = Muted, fontSize = 12.sp) }
                Text(formatDuration((record.endedAt - state.startedAt).coerceAtLeast(0)), color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Black)
            }
        }
        return
    }
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

private fun createGlobalStatsImage(context: Context, records: List<PhoneRecord>, sport: Sport): File {
    val bitmap = Bitmap.createBitmap(1080, 1600, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    fun text(value: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) {
        paint.color = color; paint.textSize = size; paint.textAlign = align
        paint.typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        canvas.drawText(value, x, y, paint)
    }
    fun score(record: PhoneRecord) = if (record.match.config.sport.isFootball) record.match.state.pointsA to record.match.state.pointsB else record.match.state.setsA to record.match.state.setsB
    val wins = records.count { score(it).let { (a, b) -> a > b } }
    val draws = records.count { score(it).let { (a, b) -> a == b } }
    val losses = records.size - wins - draws
    val duration = records.sumOf { (it.endedAt - it.match.state.startedAt).coerceAtLeast(0) }
    val states = records.map { it.match.state }
    val measuredHeart = states.map { it.averageHeartRate }.filter { it > 0 }
    val teammates = playerSummaries(records, true).take(3)
    val rivals = playerSummaries(records, false).take(3)
    canvas.drawColor(android.graphics.Color.rgb(17, 24, 32))
    paint.color = android.graphics.Color.rgb(27, 37, 48); canvas.drawRoundRect(64f, 64f, 1016f, 1536f, 28f, 28f, paint)
    text("TANTEO · ${sport.familyName.uppercase()}", 100f, 150f, 38f, android.graphics.Color.rgb(78, 179, 211), true)
    text("RESUMEN GLOBAL", 100f, 230f, 58f, android.graphics.Color.WHITE, true)
    text("${records.size} partidos", 100f, 285f, 28f, android.graphics.Color.rgb(165, 176, 189))
    val columns = listOf(Triple("GANADOS", wins, android.graphics.Color.rgb(53, 183, 121)), Triple("EMPATES", draws, android.graphics.Color.rgb(242, 184, 75)), Triple("PERDIDOS", losses, android.graphics.Color.rgb(228, 92, 98)))
    columns.forEachIndexed { index, (label, value, color) ->
        val left = 100f + index * 300f
        paint.color = color; paint.alpha = 35; canvas.drawRoundRect(left, 350f, left + 260f, 540f, 18f, 18f, paint); paint.alpha = 255
        text(value.toString(), left + 130f, 445f, 72f, color, true, Paint.Align.CENTER)
        text(label, left + 130f, 500f, 23f, android.graphics.Color.WHITE, true, Paint.Align.CENTER)
    }
    text("TIEMPO EN CANCHA", 110f, 650f, 24f, android.graphics.Color.rgb(165, 176, 189), true)
    text(formatDuration(duration), 970f, 650f, 46f, android.graphics.Color.WHITE, true, Paint.Align.RIGHT)
    text("PROMEDIO POR PARTIDO", 110f, 735f, 24f, android.graphics.Color.rgb(165, 176, 189), true)
    text(formatDuration(if (records.isEmpty()) 0 else duration / records.size), 970f, 735f, 40f, android.graphics.Color.WHITE, true, Paint.Align.RIGHT)
    if (sport.isFootball) {
        text("GOLES A FAVOR", 110f, 825f, 24f, android.graphics.Color.rgb(165, 176, 189), true)
        text(states.sumOf { it.pointsA }.toString(), 970f, 825f, 40f, android.graphics.Color.WHITE, true, Paint.Align.RIGHT)
        text("GOLES EN CONTRA", 110f, 900f, 24f, android.graphics.Color.rgb(165, 176, 189), true)
        text(states.sumOf { it.pointsB }.toString(), 970f, 900f, 40f, android.graphics.Color.WHITE, true, Paint.Align.RIGHT)
    } else {
        text("SETS", 110f, 825f, 24f, android.graphics.Color.rgb(165, 176, 189), true)
        text("${states.sumOf { it.setsA }} - ${states.sumOf { it.setsB }}", 970f, 825f, 40f, android.graphics.Color.WHITE, true, Paint.Align.RIGHT)
        text("GAMES COMPLETADOS", 110f, 900f, 24f, android.graphics.Color.rgb(165, 176, 189), true)
        text(states.sumOf { it.completedGames }.toString(), 970f, 900f, 40f, android.graphics.Color.WHITE, true, Paint.Align.RIGHT)
    }
    fun playerGrid(title: String, players: List<PlayerSummary>, left: Float, right: Float, accent: Int) {
        paint.color = android.graphics.Color.rgb(21, 31, 42); canvas.drawRoundRect(left, 950f, right, 1160f, 16f, 16f, paint)
        text(title, left + 20f, 988f, 20f, accent, true)
        paint.color = accent; paint.alpha = 24; canvas.drawRect(left + 14f, 1005f, right - 14f, 1040f, paint); paint.alpha = 255
        text("JUGADOR", left + 22f, 1030f, 15f, android.graphics.Color.rgb(165, 176, 189), true)
        val positions = listOf(right - 155f, right - 115f, right - 75f, right - 35f)
        listOf("PJ", "G", "E", "P").forEachIndexed { index, label -> text(label, positions[index], 1030f, 14f, android.graphics.Color.rgb(165, 176, 189), true, Paint.Align.CENTER) }
        players.forEachIndexed { index, player ->
            val rowY = 1072f + index * 37f
            text(player.name.take(15), left + 22f, rowY, 18f, android.graphics.Color.WHITE, true)
            listOf(player.matches, player.wins, player.draws, player.losses).forEachIndexed { valueIndex, value ->
                text(value.toString(), positions[valueIndex], rowY, 17f, when (valueIndex) { 1 -> android.graphics.Color.rgb(53, 183, 121); 2 -> android.graphics.Color.rgb(242, 184, 75); 3 -> android.graphics.Color.rgb(228, 92, 98); else -> android.graphics.Color.WHITE }, valueIndex > 0, Paint.Align.CENTER)
            }
        }
    }
    playerGrid("CON MIS COMPAÑEROS", teammates, 96f, 526f, android.graphics.Color.rgb(230, 83, 78))
    playerGrid("CONTRA RIVALES", rivals, 546f, 984f, android.graphics.Color.rgb(40, 120, 181))
    paint.color = android.graphics.Color.rgb(21, 31, 42); canvas.drawRoundRect(96f, 1190f, 984f, 1465f, 20f, 20f, paint)
    text("ACTIVIDAD ACUMULADA", 125f, 1250f, 25f, android.graphics.Color.WHITE, true)
    text("FC PROMEDIO", 125f, 1320f, 20f, android.graphics.Color.rgb(165, 176, 189), true)
    text(if (measuredHeart.isEmpty()) "-" else "${measuredHeart.average().toInt()} ppm", 125f, 1365f, 34f, android.graphics.Color.rgb(78, 179, 211), true)
    text("DISTANCIA", 540f, 1320f, 20f, android.graphics.Color.rgb(165, 176, 189), true, Paint.Align.CENTER)
    text("%.2f km".format(states.sumOf { it.distanceMeters } / 1000), 540f, 1365f, 34f, android.graphics.Color.rgb(78, 179, 211), true, Paint.Align.CENTER)
    text("CALORIAS", 955f, 1320f, 20f, android.graphics.Color.rgb(165, 176, 189), true, Paint.Align.RIGHT)
    text("${states.sumOf { it.calories }.toInt()} kcal", 955f, 1365f, 34f, android.graphics.Color.rgb(78, 179, 211), true, Paint.Align.RIGHT)
    text("PASOS", 125f, 1420f, 20f, android.graphics.Color.rgb(165, 176, 189), true)
    text(states.sumOf { it.steps }.toString(), 955f, 1420f, 28f, android.graphics.Color.rgb(78, 179, 211), true, Paint.Align.RIGHT)
    val directory = File(context.cacheDir, "shared_matches").apply { mkdirs() }
    return File(directory, "tanteo-resumen-${sport.familyName.lowercase()}-${System.currentTimeMillis()}.png").also { file ->
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
}

private fun shareGlobalStatsImage(context: Context, records: List<PhoneRecord>, sport: Sport) {
    val file = createGlobalStatsImage(context, records, sport)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Compartir resumen"))
}

private fun saveGlobalStatsImage(context: Context, records: List<PhoneRecord>, sport: Sport) {
    val file = createGlobalStatsImage(context, records, sport)
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Tanteo")
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    if (uri != null) {
        context.contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
        Toast.makeText(context, "Resumen guardado en Galeria", Toast.LENGTH_SHORT).show()
    } else Toast.makeText(context, "No se pudo guardar el resumen", Toast.LENGTH_SHORT).show()
}

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
    text("TANTEO · ${config.sport.displayName.uppercase()}", 96f, 135f, 34f, android.graphics.Color.rgb(78, 179, 211), true)
    text(formatDate(record.endedAt), 984f, 135f, 28f, android.graphics.Color.rgb(165, 176, 189), align = Paint.Align.RIGHT)
    text("RESULTADO FINAL", 540f, 235f, 28f, android.graphics.Color.rgb(165, 176, 189), true, Paint.Align.CENTER)
    text(if (config.sport.isFootball) "${state.pointsA}  -  ${state.pointsB}" else "${state.setsA}  -  ${state.setsB}", 540f, 355f, 104f, android.graphics.Color.WHITE, true, Paint.Align.CENTER)
    text(sideLabel(config, true), 280f, 455f, 34f, shareColors.getOrElse(config.colorA) { shareColors[0] }, true, Paint.Align.CENTER)
    text(sideLabel(config, false), 800f, 455f, 34f, shareColors.getOrElse(config.colorB) { shareColors[1] }, true, Paint.Align.CENTER)
    paint.color = android.graphics.Color.rgb(52, 66, 80); canvas.drawRect(96f, 520f, 984f, 522f, paint)
    text("DURACION", 120f, 610f, 26f, android.graphics.Color.rgb(165, 176, 189), true)
    text(formatDuration((record.endedAt - state.startedAt).coerceAtLeast(0)), 960f, 610f, 48f, android.graphics.Color.WHITE, true, Paint.Align.RIGHT)
    var y = 755f
    if (config.sport.isFootball) {
        y = 700f
        if (state.goalEvents.isNotEmpty()) {
            text("CRONOLOGIA DE GOLES", 540f, y, 24f, android.graphics.Color.rgb(165, 176, 189), true, Paint.Align.CENTER)
            var scoreA = 0; var scoreB = 0
            val shownGoals = state.goalEvents.take(12)
            val twoColumns = shownGoals.size > 6
            val rowsPerColumn = if (twoColumns) (shownGoals.size + 1) / 2 else shownGoals.size
            state.goalEvents.take(12).forEachIndexed { index, goal ->
                if (goal.teamA) scoreA++ else scoreB++
                val secondColumn = twoColumns && index >= rowsPerColumn
                val columnX = if (!twoColumns) 300f else if (secondColumn) 560f else 120f
                val rowY = y + 45f + (if (secondColumn) index - rowsPerColumn else index) * 36f
                text(if (goal.teamA) "Mi Equipo" else "Rival", columnX, rowY, 20f, if (goal.teamA) shareColors[0] else shareColors[1], true)
                text("$scoreA-$scoreB", columnX + 130f, rowY, 22f, android.graphics.Color.WHITE, true)
                text(formatGoalTime(goal.elapsedMillis), columnX + 330f, rowY, 20f, android.graphics.Color.rgb(78, 179, 211), true, Paint.Align.RIGHT)
            }
            y += 45f + rowsPerColumn * 36f
            if (state.goalEvents.size > 12) text("+${state.goalEvents.size - 12} goles", 540f, y, 18f, android.graphics.Color.rgb(165, 176, 189), align = Paint.Align.CENTER)
        }
        val own = config.teamAPlayers.filter { it.isNotBlank() && !it.equals(config.userPlayer, true) }.joinToString(", ").ifBlank { "Sin cargar" }
        val rivals = config.teamBPlayers.filter { it.isNotBlank() }.joinToString(", ").ifBlank { "Sin cargar" }
        text("INTEGRANTES", 120f, y + 10f, 24f, android.graphics.Color.rgb(165, 176, 189), true)
        text("Compañeros", 120f, y + 52f, 21f, shareColors[0], true)
        text(own.take(58), 300f, y + 52f, 21f, android.graphics.Color.WHITE)
        text("Rivales", 120f, y + 90f, 21f, shareColors[1], true)
        text(rivals.take(65), 300f, y + 90f, 21f, android.graphics.Color.WHITE)
    } else {
        text("SET", 120f, 690f, 24f, android.graphics.Color.rgb(165, 176, 189), true)
        text("RESULTADO", 540f, 690f, 24f, android.graphics.Color.rgb(165, 176, 189), true, Paint.Align.CENTER)
        text("DURACION", 960f, 690f, 24f, android.graphics.Color.rgb(165, 176, 189), true, Paint.Align.RIGHT)
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
    }
    if (!config.sport.isFootball) text("${state.gameDurations.size} games jugados", 540f, 1025f, 27f, android.graphics.Color.rgb(165, 176, 189), align = Paint.Align.CENTER)
    val hasActivity = state.averageHeartRate > 0 || state.maxHeartRate > 0 || state.distanceMeters > 0 || state.steps > 0 || state.calories > 0
    if (hasActivity) {
        paint.color = android.graphics.Color.rgb(21, 31, 42)
        canvas.drawRoundRect(96f, 1060f, 984f, 1260f, 20f, 20f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = android.graphics.Color.rgb(52, 66, 80)
        canvas.drawRoundRect(96f, 1060f, 984f, 1260f, 20f, 20f, paint)
        paint.style = Paint.Style.FILL
        text("ACTIVIDAD", 128f, 1110f, 25f, android.graphics.Color.WHITE, true)
    fun metric(label: String, value: String, x: Float, labelY: Float, align: Paint.Align = Paint.Align.LEFT) {
        text(label, x, labelY, 19f, android.graphics.Color.rgb(165, 176, 189), true, align)
        text(value, x, labelY + 35f, 28f, android.graphics.Color.rgb(78, 179, 211), true, align)
    }
        metric("RITMO PROMEDIO", if (state.averageHeartRate > 0) "${state.averageHeartRate.toInt()} ppm" else "-", 128f, 1150f)
        metric("RITMO MAXIMO", if (state.maxHeartRate > 0) "${state.maxHeartRate.toInt()} ppm" else "-", 540f, 1150f, Paint.Align.CENTER)
        metric("DISTANCIA", if (state.distanceMeters > 0) "%s%.2f km".format(if (state.distanceEstimated) "~" else "", state.distanceMeters / 1000) else "-", 952f, 1150f, Paint.Align.RIGHT)
        metric("PASOS", if (state.steps > 0) state.steps.toString() else "-", 128f, 1215f)
        metric("CALORIAS", if (state.calories > 0) "${state.calories.toInt()} kcal" else "-", 952f, 1215f, Paint.Align.RIGHT)
    }
    val directory = File(context.cacheDir, "shared_matches").apply { mkdirs() }; val file = File(directory, "tanteo-${record.endedAt}.png")
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
        put(MediaStore.Images.Media.DISPLAY_NAME, "Tanteo-${record.endedAt}.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Tanteo")
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    if (uri != null) {
        context.contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
        Toast.makeText(context, "Imagen guardada en Galeria", Toast.LENGTH_SHORT).show()
    } else Toast.makeText(context, "No se pudo guardar la imagen", Toast.LENGTH_SHORT).show()
}
private fun formatDate(ms: Long): String = SimpleDateFormat("dd/MM/yyyy · HH:mm", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("America/Argentina/Buenos_Aires") }.format(Date(ms))
