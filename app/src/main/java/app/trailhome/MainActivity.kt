@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
package app.trailhome

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.location.LocationManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

class MainActivity : ComponentActivity(), SensorEventListener {
    // Компас работает, пока приложение на экране (в фоне его ведёт сервис)
    override fun onResume() {
        super.onResume()
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR), SensorManager.SENSOR_DELAY_UI)
    }
    override fun onPause() { super.onPause(); (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(this) }
    override fun onSensorChanged(e: SensorEvent) {
        val r = FloatArray(9); val o = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(r, e.values); SensorManager.getOrientation(r, o)
        Store.azimuth = ((Math.toDegrees(o[0].toDouble()) + 360) % 360).toFloat()
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    override fun onCreate(b: Bundle?) {
        super.onCreate(b); Store.init(this)
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(filesDir, "osm"); osmdroidTileCache = File(filesDir, "osm/tiles")
            tileFileSystemCacheMaxBytes = 1_000_000_000L; tileFileSystemCacheTrimBytes = 800_000_000L
        }
        setContent { Theme { App() } }
    }
}

@Composable fun Theme(c: @Composable () -> Unit) {
    val dark = when (Store.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val s = if (dark) darkColorScheme(primary = Color(0xFF7BD88F), onPrimary = Color(0xFF00391A), secondary = Color(0xFFE6B450),
        background = Color(0xFF0F1A14), surface = Color(0xFF0F1A14), surfaceVariant = Color(0xFF1F3327), onSurfaceVariant = Color(0xFFD5E8DA))
    else lightColorScheme(primary = Color(0xFF1B6B3A), secondary = Color(0xFF9A6A00), background = Color(0xFFF3F8F4), surface = Color(0xFFF3F8F4),
        surfaceVariant = Color(0xFFDDEBE0), onSurfaceVariant = Color(0xFF1B2B20))
    MaterialTheme(colorScheme = s, content = c)
}

fun prime(c: Context) {
    if (Store.loc != null || c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
    runCatching {
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        (lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))
            ?.let { Store.loc = Pt(it.latitude, it.longitude, System.currentTimeMillis()) }
    }
}

@Composable fun App() {
    val ctx = LocalContext.current
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { prime(ctx) }
    LaunchedEffect(Unit) {
        prime(ctx)
        perm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS))
    }
    val items = listOf(tr("Навигация", "Navigate") to Icons.Default.Explore, tr("Карта", "Map") to Icons.Default.Map,
        tr("Метки", "Marks") to Icons.Default.Place, tr("Настройки", "Settings") to Icons.Default.Settings)
    BackHandler(Store.tab == 4) { Store.tab = 3 }
    Scaffold(bottomBar = {
        NavigationBar { items.forEachIndexed { i, (n, ic) -> NavigationBarItem(Store.tab == i || (i == 3 && Store.tab == 4), { Store.tab = i }, { Icon(ic, n) }, label = { Text(n, maxLines = 1) }) } }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (Store.tab) {
                0 -> NavScreen(); 1 -> MapScreen(); 2 -> MarksScreen(); 3 -> SettingsScreen()
                else -> Column(Modifier.fillMaxSize()) {
                    Box(Modifier.padding(16.dp, 12.dp, 16.dp, 0.dp)) { Seg(listOf(SegItem(tr("Назад", "Back"), Icons.Default.ArrowBack) { Store.tab = 3 })) }
                    TrackScreen()
                }
            }
        }
    }
    if (Store.showHelp) AlertDialog({ Store.showHelp = false }, title = { Text(tr("Инструкция", "User guide")) },
        text = { Text(if (L.en) HELP_EN else HELP_RU, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton({ Store.showHelp = false }) { Text(tr("Закрыть", "Close")) } })
}
@Composable fun VoiceField(v: String, onV: (String) -> Unit, label: String, kb: KeyboardType = KeyboardType.Text, append: Boolean = true, multiline: Boolean = false) {
    val ctx = LocalContext.current
    // Голос всегда дописывается к тексту; начало текста и новое предложение — с большой буквы
    val l = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { raw ->
            val t = v.trimEnd()
            val sentence = t.isEmpty() || t.endsWith(".") || t.endsWith("!") || t.endsWith("?")
            val piece = if (sentence) raw.replaceFirstChar { it.uppercase() } else raw
            onV(if (t.isEmpty()) piece else "$t $piece")
        }
    }
    OutlinedTextField(v, onV, Modifier.fillMaxWidth(), label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = kb), shape = RoundedCornerShape(14.dp),
        singleLine = !multiline, maxLines = if (multiline) 5 else 1,
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (v.isNotEmpty()) IconButton({ onV("") }) { Icon(Icons.Default.Close, tr("Очистить", "Clear")) }
                IconButton({
                    val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (L.en) "en-US" else "ru-RU").putExtra(RecognizerIntent.EXTRA_PROMPT, label)
                    try { l.launch(i) } catch (e: Exception) { Toast.makeText(ctx, tr("Голосовой ввод недоступен", "Voice input unavailable"), Toast.LENGTH_SHORT).show() }
                }) { Icon(Icons.Default.Mic, tr("Голосовой ввод", "Voice input")) }
            }
        })
}

@Composable fun ConfirmDialog(text: String, onYes: () -> Unit, onNo: () -> Unit) {
    AlertDialog(onNo, title = { Text(tr("Подтвердите", "Confirm")) }, text = { Text(text) },
        confirmButton = { TextButton({ onYes() }) { Text(tr("Удалить", "Delete")) } }, dismissButton = { TextButton(onNo) { Text(tr("Отмена", "Cancel")) } })
}
@Composable fun MarkDialog(m: Mark?, lat0: Double?, lon0: Double?, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var confirmDel by remember { mutableStateOf(false) }
    fun f(d: Double?) = d?.let { String.format(Locale.US, "%.6f", it) } ?: ""
    var n by remember { mutableStateOf(m?.name ?: "") }; var note by remember { mutableStateOf(m?.note ?: "") }
    var la by remember { mutableStateOf(f(m?.lat ?: lat0 ?: Store.loc?.lat)) }; var lo by remember { mutableStateOf(f(m?.lon ?: lon0 ?: Store.loc?.lon)) }
    LaunchedEffect(Store.loc) { if (m == null && lat0 == null && la.isBlank()) Store.loc?.let { la = f(it.lat); lo = f(it.lon) } }
    AlertDialog(onDone, title = { Text(if (m == null) tr("Новая метка", "New mark") else tr("Метка", "Mark")) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            VoiceField(n, { n = it }, tr("Название", "Name"))
            VoiceField(note, { note = it }, tr("Заметка", "Note"), append = true, multiline = true)
            VoiceField(la, { la = it }, tr("Широта", "Latitude"), KeyboardType.Decimal)
            VoiceField(lo, { lo = it }, tr("Долгота", "Longitude"), KeyboardType.Decimal)
            if (m != null) Seg(listOf(
                SegItem(tr("Цель", "Target"), Icons.Default.Navigation) { goTo(ctx, m.id); Store.tab = 0; onDone() },
                SegItem(tr("Навигатор", "Navigator"), Icons.Default.Directions) { openMap(ctx, la.replace(',', '.').toDoubleOrNull() ?: m.lat, lo.replace(',', '.').toDoubleOrNull() ?: m.lon, n.ifBlank { m.name }) },
                SegItem(tr("Удалить", "Delete"), Icons.Default.Delete) { confirmDel = true }))
        }
    }, confirmButton = {
        TextButton({
            val a = la.replace(',', '.').toDoubleOrNull(); val o = lo.replace(',', '.').toDoubleOrNull()
            if (a == null || o == null) Toast.makeText(ctx, tr("Укажите координаты", "Enter coordinates"), Toast.LENGTH_LONG).show()
            else {
                val name = n.trim().replaceFirstChar { it.uppercase() }.ifBlank { tr("Метка", "Mark") + " ${Store.marks.size + 1}" }
                if (m == null) Store.addMark(name, a, o, note) else Store.updateMark(m.copy(name = name, lat = a, lon = o, note = note))
                onDone()
            }
        }) { Text(tr("Сохранить", "Save")) }
    }, dismissButton = { TextButton(onDone) { Text(tr("Отмена", "Cancel")) } })
    if (confirmDel && m != null) ConfirmDialog(tr("Удалить метку «", "Delete mark \"") + m.name + tr("»?", "\"?"),
        { Store.marks.remove(m); Store.saveMarks(); if (Store.targetId == m.id) Store.select(-1); confirmDel = false; onDone() }, { confirmDel = false })
}
@Composable fun MapChoices() {
    Choice(tr("Карта", "Map"), MapSources.provider(), listOf("google" to "Google", "yandex" to tr("Яндекс", "Yandex"), "osm" to "OSM")) {
        Store.provider = it; Store.sp?.edit()?.putString("provider", it)?.apply()
    }
    Choice(tr("Тип карты", "Map type"), Store.mapType, listOf("normal" to tr("Обычная", "Normal"), "satellite" to tr("Спутниковая", "Satellite"),
        "hybrid" to tr("Гибридная", "Hybrid"), "terrain" to tr("Ландшафт", "Terrain")), true) {
        Store.mapType = it; Store.sp?.edit()?.putString("maptype", it)?.apply()
    }
    Text(tr("Сейчас: ", "Now: ") + when (MapSources.provider()) { "yandex" -> tr("Яндекс", "Yandex"); "google" -> "Google"; else -> "OSM" } +
        if (MapSources.provider() == "yandex" && Store.mapType == "terrain") tr(" (ландшафта нет, показана обычная)", " (no terrain layer, showing standard)") else "",
        style = MaterialTheme.typography.labelMedium)
}

@Composable fun MapScreen() {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<GeoPoint?>(null) }; var sel by remember { mutableStateOf<Mark?>(null) }; var opts by remember { mutableStateOf(false) }
    val ic = remember { MapIcons(ctx) }
    val key = MapSources.key()
    val base = remember(key) { MapSources.base(key) }
    val lp = remember(key) { MapSources.overlaySource(key)?.let { MapTileProviderBasic(ctx, it) } }
    val labels = remember(key) { lp?.let { p -> TilesOverlay(p, ctx).apply { loadingBackgroundColor = 0; loadingLineColor = 0 } } }
    DisposableEffect(key) { onDispose { lp?.detach() } }
    val map = remember {
        MapView(ctx).apply {
            setMultiTouchControls(true); controller.setZoom(16.0)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        }
    }
    DisposableEffect(Unit) { map.onResume(); onDispose { map.onPause(); map.onDetach() } }
    LaunchedEffect(key) { Store.loc?.let { map.controller.setCenter(GeoPoint(Proj.f(it.lat), it.lon)) } }
    LaunchedEffect(Store.focus) { Store.focus?.let { map.controller.setZoom(17.0); map.controller.animateTo(GeoPoint(Proj.f(it.lat), it.lon)); Store.focus = null } }
    LaunchedEffect(map) { snapshotFlow { if (Store.compass) -Store.azimuth else 0f }.collect { if (abs(map.mapOrientation - it) > 2f) map.mapOrientation = it } }
    Box(Modifier.fillMaxSize()) {
        AndroidView({ map }, Modifier.fillMaxSize(), update = { m ->
            if (m.tileProvider.tileSource.name() != base.name()) m.setTileSource(base)
            m.overlays.clear()
            labels?.let { m.overlays.add(it) }
            m.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { pending = GeoPoint(Proj.inv(p.latitude), p.longitude); return true }
                override fun longPressHelper(p: GeoPoint): Boolean { pending = GeoPoint(Proj.inv(p.latitude), p.longitude); return true }
            }))
            val pts = Store.track.takeLast(3000).map { GeoPoint(Proj.f(it.lat), it.lon) }
            m.overlays.add(Polyline().apply { setPoints(pts); outlinePaint.color = 0xFFFFFFFF.toInt(); outlinePaint.strokeWidth = 17f; setOnClickListener { _, _, _ -> false } })
            m.overlays.add(Polyline().apply { setPoints(pts); outlinePaint.color = 0xFFD81B60.toInt(); outlinePaint.strokeWidth = 9f; setOnClickListener { _, _, _ -> false } })
            Store.marks.sortedBy { it.id == Store.targetId }.forEach { mk ->
                val start = mk.id == Store.startId || mk.name.startsWith("Старт") || mk.name.startsWith("Start")
                m.overlays.add(Marker(m).apply {
                    position = GeoPoint(Proj.f(mk.lat), mk.lon); title = mk.name; icon = ic.pin(start, mk.id == Store.targetId)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    setOnMarkerClickListener { _, _ -> sel = mk; true }
                })
            }
            Store.loc?.let { me -> m.overlays.add(Marker(m).apply {
                position = GeoPoint(Proj.f(me.lat), me.lon); icon = ic.me
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); setOnMarkerClickListener { _, _ -> false }
            }) }
            m.invalidate()
        })
        SmallFloatingActionButton({ Store.switchCompass(!Store.compass) }, Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 128.dp),
            containerColor = if (Store.compass) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant) { Icon(Icons.Default.Explore, tr("Компас", "Compass")) }
        SmallFloatingActionButton({ opts = true }, Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 72.dp)) { Icon(Icons.Default.Layers, tr("Слои", "Layers")) }
        Surface(Modifier.align(Alignment.TopStart).padding(8.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .85f)) {
            Text(satText(), Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
        }
        SmallFloatingActionButton({ Store.loc?.let { map.controller.animateTo(GeoPoint(Proj.f(it.lat), it.lon)) } }, Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(Icons.Default.MyLocation, tr("Я здесь", "My location"))
        }
    }
    if (opts) AlertDialog({ opts = false }, title = { Text(tr("Карта", "Map")) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { MapChoices() } },
        confirmButton = { TextButton({ opts = false }) { Text(tr("ОК", "OK")) } })
    pending?.let { p -> MarkDialog(null, p.latitude, p.longitude) { pending = null } }
    sel?.let { s -> MarkDialog(s, null, null) { sel = null } }
}
@Composable fun Radar(mod: Modifier) {
    val me = Store.loc; val t = Store.target(); val az = (me?.let { Store.curHeading(it) } ?: Store.azimuth.toDouble()).toFloat()
    val pri = MaterialTheme.colorScheme.primary; val sec = MaterialTheme.colorScheme.secondary
    val line = MaterialTheme.colorScheme.onSurface.copy(alpha = .25f)
    Canvas(mod) {
        val c = center; val r = size.minDimension / 2
        drawCircle(line, r, c, style = Stroke(2f)); drawCircle(line, r / 2, c, style = Stroke(2f))
        drawLine(line, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 2f); drawLine(line, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 2f)
        val na = Math.toRadians(-az.toDouble())   // красная точка — север
        drawCircle(Color(0xFFE53935), 9f, Offset(c.x + r * .9f * sin(na).toFloat(), c.y - r * .9f * cos(na).toFloat()))
        if (me == null) return@Canvas
        val big = max(60.0, (t?.let { Geo.dist(me.lat, me.lon, it.lat, it.lon) } ?: 200.0) * 1.3)
        val k = (r / big).toFloat(); val a = Math.toRadians(az.toDouble())
        fun xy(la: Double, lo: Double): Offset {
            val dx = (lo - me.lon) * 111320 * cos(Math.toRadians(me.lat)); val dn = (la - me.lat) * 110540
            return Offset(c.x + ((dx * cos(a) - dn * sin(a)) * k).toFloat(), c.y - ((dx * sin(a) + dn * cos(a)) * k).toFloat())
        }
        val pts = Store.track.takeLast(500).map { xy(it.lat, it.lon) }
        for (i in 1 until pts.size) drawLine(pri.copy(alpha = .6f), pts[i - 1], pts[i], 4f)
        Store.marks.forEach { drawCircle(if (it.id == t?.id) sec else pri, if (it.id == t?.id) 14f else 8f, xy(it.lat, it.lon)) }
        if (t != null) drawLine(sec, c, xy(t.lat, t.lon), 5f)
        drawCircle(Color.White, 10f, c)
    }
}

@Composable fun NavScreen() {
    val ctx = LocalContext.current
    var add by remember { mutableStateOf(false) }; var pick by remember { mutableStateOf(false) }
    val me = Store.loc; val t = Store.target(); val st = Store.startMark()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), Arrangement.spacedBy(12.dp), Alignment.CenterHorizontally) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(24.dp)) {
            Radar(Modifier.fillMaxWidth().aspectRatio(1f).padding(12.dp))
        }
        Text(satText(), style = MaterialTheme.typography.labelMedium)
        if (me != null && t != null) {
            val d = Geo.dist(me.lat, me.lon, t.lat, t.lon)
            Text(if (d < 15) tr("На месте", "Arrived") else Store.curHeading(me)?.let { turnText(Geo.rel(me, t, it)) } ?: tr("Идите — определю направление", "Keep moving to get a direction"), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text(t.name + " · " + Geo.fmt(d), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (Store.guiding) tr("Голосовые подсказки включены", "Voice guidance on") else tr("Подсказки выключены", "Guidance off"), style = MaterialTheme.typography.labelMedium)
        } else Text(
            if (!Store.recording) tr("Нажмите «Старт», чтобы начать запись маршрута", "Press Start to begin recording your route")
            else if (me == null) tr("Ищу спутники…", "Searching for satellites…")
            else tr("Идёт запись, подсказки молчат. Выберите цель, чтобы вернуться", "Recording quietly. Choose a target to go back"))
        Seg(listOf(
            SegItem(if (Store.recording) tr("Стоп", "Stop") else tr("Старт", "Start"), if (Store.recording) Icons.Default.Stop else Icons.Default.PlayArrow, Store.recording) {
                val i = Intent(ctx, NavService::class.java)
                if (Store.recording) ctx.stopService(i) else startNav(ctx)
            },
            SegItem(tr("Метка", "Mark"), Icons.Default.MyLocation) { add = true },
            SegItem(tr("Компас", "Compass"), Icons.Default.Explore, selected = Store.compass) { Store.switchCompass(!Store.compass) }), fill = true)
        Seg(listOf(
            SegItem(tr("К старту", "To start"), Icons.Default.Home, enabled = st != null) { goTo(ctx, st!!.id) },
            SegItem(tr("Цель", "Target"), Icons.Default.Place) { pick = true },
            SegItem(if (Store.guiding) tr("Тишина", "Silence") else tr("Голос", "Voice"), if (Store.guiding) Icons.Default.VolumeOff else Icons.Default.VolumeUp, selected = !Store.guiding && t != null, enabled = t != null) { Store.guiding = !Store.guiding }), fill = true)
    }
    if (add) MarkDialog(null, null, null) { add = false }
    if (pick) TargetDialog { pick = false }
}
@Composable fun MarksScreen() {
    val ctx = LocalContext.current
    var add by remember { mutableStateOf(false) }; var edit by remember { mutableStateOf<Mark?>(null) }; var q by remember { mutableStateOf("") }
    var starts by remember { mutableStateOf(false) }; var clear by remember { mutableStateOf(false) }
    val exp = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { u ->
        u?.let { ctx.contentResolver.openOutputStream(it)?.use { o -> o.write(Store.toGpx().toByteArray()) }; Toast.makeText(ctx, tr("Экспортировано", "Exported"), Toast.LENGTH_SHORT).show() }
    }
    val imp = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        u?.let {
            val s = ctx.contentResolver.openInputStream(it)?.bufferedReader()?.readText() ?: ""
            Toast.makeText(ctx, tr("Импортировано меток: ", "Imported marks: ") + Store.fromGpx(s), Toast.LENGTH_SHORT).show()
        }
    }
    val nStarts = Store.marks.count { Store.isStart(it) }; val nMarks = Store.marks.size - nStarts
    val shown = Store.marks.filter { Store.isStart(it) == starts && (it.name.contains(q, true) || it.note.contains(q, true)) }.let { if (starts) it.reversed() else it }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Seg(listOf(
            SegItem(tr("Метки", "Marks") + " ($nMarks)", selected = !starts) { starts = false },
            SegItem(tr("Старты", "Starts") + " ($nStarts)", selected = starts) { starts = true }), fill = true)
        VoiceField(q, { q = it }, if (starts) tr("Поиск старта", "Search starts") else tr("Поиск метки", "Search marks"))
        if (!starts) Seg(listOf(
            SegItem(tr("Метка", "Mark"), Icons.Default.Add) { add = true },
            SegItem(tr("Экспорт", "Export"), Icons.Default.FileUpload) { exp.launch("trailhome.gpx") },
            SegItem(tr("Импорт", "Import"), Icons.Default.FileDownload) { imp.launch(arrayOf("*/*")) }), fill = true)
        else Seg(listOf(SegItem(tr("Очистить старты", "Clear starts"), Icons.Default.Delete, enabled = nStarts > 0) { clear = true }))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.id }) { m ->
                Card({ edit = m }, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (m.note.isNotBlank()) Text(m.note.replace('\n', ' '), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("%.5f, %.5f".format(Locale.US, m.lat, m.lon), style = MaterialTheme.typography.labelSmall)
                            Store.loc?.let { Text(Geo.fmt(Geo.dist(it.lat, it.lon, m.lat, m.lon)), color = MaterialTheme.colorScheme.secondary) }
                        }
                        IconButton({ Store.focus = m; Store.tab = 1 }) { Icon(Icons.Default.Map, tr("На карте", "On map")) }
                        IconButton({
                            goTo(ctx, m.id); Store.tab = 0
                            Toast.makeText(ctx, tr("Цель: ", "Target: ") + m.name, Toast.LENGTH_SHORT).show()
                        }) { Icon(Icons.Default.Navigation, tr("Цель", "Target"), tint = if (m.id == Store.targetId) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
    if (add) MarkDialog(null, null, null) { add = false }
    edit?.let { m -> MarkDialog(m, null, null) { edit = null } }
    if (clear) ConfirmDialog(tr("Удалить все старты? Метки останутся.", "Delete all starts? Marks will stay."), {
        if (Store.target()?.let { Store.isStart(it) } == true) Store.select(-1)
        Store.marks.removeAll { Store.isStart(it) }; Store.saveMarks(); Store.markStart(-1); clear = false
    }, { clear = false })
}
@Composable fun TrackScreen() {
    var p by remember { mutableIntStateOf(0) }
    val span = longArrayOf(3600000L, 86400000L, Long.MAX_VALUE)[p]
    val now = System.currentTimeMillis()
    val pts = Store.track.filter { now - it.t <= span }
    val f = SimpleDateFormat(if (p == 2) "dd.MM" else if (p == 1) "dd.MM HH:00" else "HH:mm", Locale.getDefault())
    var total = 0.0; val by = linkedMapOf<String, Double>()
    for (i in 1 until pts.size) {
        val d = Geo.dist(pts[i - 1].lat, pts[i - 1].lon, pts[i].lat, pts[i].lon); total += d
        by.merge(f.format(Date(pts[i].t)).let { if (p == 0) it.dropLast(1) + "0" else it }, d, Double::plus)
    }
    val dur = if (pts.size > 1) pts.last().t - pts.first().t else 0L
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), Arrangement.spacedBy(10.dp)) {
        Seg(listOf(
            SegItem(tr("Час", "Hour"), selected = p == 0) { p = 0 },
            SegItem(tr("Сутки", "Day"), selected = p == 1) { p = 1 },
            SegItem(tr("Всё", "All"), selected = p == 2) { p = 2 }), fill = true)
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(tr("Пройдено: ", "Distance: ") + Geo.fmt(total), style = MaterialTheme.typography.titleLarge)
                Text(tr("Время: ", "Time: ") + "${dur / 3600000} ${tr("ч", "h")} ${dur / 60000 % 60} ${tr("мин", "min")} • ${tr("точек", "points")}: ${pts.size}")
                if (dur > 0) Text(tr("Средняя скорость: ", "Average speed: ") + "%.1f ${tr("км/ч", "km/h")}".format(total / (dur / 1000.0) * 3.6))
            }
        }
        Text(if (p == 2) tr("По дням", "By day") else tr("По периодам", "By period"), style = MaterialTheme.typography.titleMedium)
        by.forEach { (k, v) -> Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(k); Text(Geo.fmt(v), color = MaterialTheme.colorScheme.secondary) } }
        Seg(listOf(SegItem(tr("Очистить трек", "Clear track"), Icons.Default.Delete) { Store.track.clear(); Store.saveTrack() }))
    }
}
const val HELP_RU = """1. «Навигация» → «Старт»: начинается запись маршрута. Приложение молчит и только пишет трек. В точке старта создаётся метка «Старт №N …».
2. Чтобы вернуться, нажмите «К старту» или выберите любую другую метку: кнопка «Цель», значок стрелки у метки или кнопка «Цель» в окне метки. GPS включится сам, если он выключен. С этого момента голос подсказывает по-простому: «Развернитесь», «Поверните направо», «Держитесь правее на 30 градусов» — и называет расстояние до цели. Когда вы на месте, голос скажет «На месте» и замолчит; у стартовой точки запись маршрута остановится сама.
3. Кнопка «Тишина» выключает подсказки (запись маршрута продолжается), кнопка «Голос» включает их снова. Интервал подсказок меняется в настройках.

Подсказки работают в фоне, экран можно выключить. Держите телефон горизонтально — компас точнее.

Компас (кнопка на «Навигации» и «Карте», а также в настройках): включён — направление по компасу телефона, карта и радар поворачиваются вслед за телефоном; выключен — по вашему перемещению за интервал (нужно идти). Голос говорит через заданное число секунд или когда вы сместились на заданное число метров.

Спутники: «видно» — сколько спутников над горизонтом, «используется» — сколько участвует в определении места (появляются после «Старт»).

Радар: верх — туда, куда повёрнут телефон; жёлтая линия — к цели; зелёная ломаная — ваш путь.

Карта: нажмите или удерживайте палец на карте — откроется окно новой метки с этими координатами. Нажмите на метку, чтобы изменить её или сделать целью. Кнопка со слоями выбирает источник карты (Google, Яндекс, OSM) и тип. Значки: фиолетовый с домиком — старт, оранжевый — метка, крупный с жёлтым ореолом — цель, синяя точка — вы, розовая линия — ваш путь. Карта OpenStreetMap подгружается из интернета и сохраняется в память: заранее просмотрите нужный район дома, чтобы он был доступен в лесу без связи.

Метки: нажмите на метку или старт — откроется окно с названием, заметкой и координатами. «Цель» ведёт к метке, «Навигатор» открывает список приложений с картами для маршрута до этих координат (например, на авто), «Удалить» удаляет метку после подтверждения. В списке заметка показана одной строкой. Новое окно метки подставляет ваши координаты, их можно изменить. Новое надиктованное название заменяет старое, заметка дополняется. Экспорт и импорт — GPX.

Трек: путь за час, сутки или всё время с разбивкой по периодам и дням.

Инструкция и статистика трека за час, сутки и всё время — на вкладке «Настройки». Микрофон есть в каждом поле ввода.

Советы: разрешите геолокацию «всегда» и отключите экономию батареи для приложения."""
const val HELP_EN = """1. Navigate → Start: route recording begins. The app stays silent and only records the track. A "Start #N …" mark is created at your position.
2. To go back, press "To start" or choose any other mark: the "Target" button, the arrow icon on a mark or the "Target" button in the mark window. GPS turns on by itself if it was off. From then on the voice gives plain instructions: "Turn around", "Turn right", "Bear right by 30 degrees" — and the distance to the target. When you arrive it says "Arrived" and goes quiet; at the start point recording stops by itself.
3. "Silence" turns guidance off while the route keeps recording; "Voice" turns it back on. The guidance interval is set in Settings.

Guidance works in the background, you can turn the screen off. Hold the phone flat for a more accurate compass.

Compass (button on Navigate and Map, also in Settings): on — direction from the phone compass, the map and radar turn with the phone; off — from your movement over the interval (keep walking). The voice speaks after the set number of seconds or when you have moved the set number of meters.

Satellites: "visible" — how many are above the horizon, "used" — how many take part in your position fix (shown after Start).

Radar: up is where the phone points; the yellow line leads to the target; the green line is your path.

Map: tap or long-press the map to open a new mark window with those coordinates. Tap a mark to edit it or make it the target. The layers button chooses the map source (Google, Yandex, OSM) and type. Icons: purple with a house — start, orange — mark, large with a yellow halo — target, blue dot — you, pink line — your path. The OpenStreetMap map is loaded from the internet and cached: browse the area at home beforehand to have it in the forest without signal.

Marks: tap a mark or a start to open a window with its name, note and coordinates. "Target" guides you to it, "Navigator" opens the list of map apps for a route to those coordinates (e.g. by car), "Delete" removes the mark after confirmation. The list shows the note on one line. A new mark window fills in your coordinates, you can edit them. A newly dictated name replaces the old one, a dictated note is appended. Export and import use GPX.

Track: your path for an hour, a day or all time, grouped by period and day.

The guide and track statistics for an hour, a day and all time are on the Settings tab. Every input field has a microphone.

Tips: allow location "all the time" and disable battery optimization for the app."""
@Composable fun Choice(title: String, cur: String, opts: List<Pair<String, String>>, grid: Boolean = false, on: (String) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    val items = opts.map { (k, l) -> SegItem(l, selected = cur == k) { on(k) } }
    if (grid) items.chunked(2).forEach { Seg(it, fill = true) } else Seg(items)
}
@Composable fun SettingsScreen() {
    val ctx = LocalContext.current
    val sp = remember { ctx.getSharedPreferences("s", 0) }
    var v by remember { mutableStateOf(sp.getBoolean("voice", true)) }
    var iv by remember { mutableFloatStateOf(sp.getInt("interval", 30).toFloat()) }
    var dm by remember { mutableFloatStateOf(sp.getInt("dist", 15).toFloat()) }
    var vname by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf("") }
    var showVoices by remember { mutableStateOf(false) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(L.en) {
        var t: TextToSpeech? = null
        t = TextToSpeech(ctx) { if (it == TextToSpeech.SUCCESS) t?.let { x -> Speaker.pick(x, ctx); vname = x.voice?.name ?: ""; tts = x } }
        onDispose { t?.shutdown(); tts = null }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), Arrangement.spacedBy(10.dp)) {
        Choice(tr("Язык интерфейса", "Language"), L.mode, listOf("system" to tr("Системный", "System"), "en" to "English", "ru" to "Русский")) {
            L.mode = it; sp.edit().putString("lang", it).apply()
        }
        Choice(tr("Тема", "Theme"), Store.theme, listOf("system" to tr("Системная", "System"), "light" to tr("Светлая", "Light"), "dark" to tr("Тёмная", "Dark"))) {
            Store.theme = it; sp.edit().putString("theme", it).apply()
        }
        MapChoices()
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text(tr("Голосовые подсказки", "Voice guidance")); Switch(v, { v = it; sp.edit().putBoolean("voice", it).apply() })
        }
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text(tr("Компас", "Compass")); Switch(Store.compass, { Store.compass = it; sp.edit().putBoolean("compass", it).apply() })
        }
        Text(if (Store.compass) tr("Направление — по компасу телефона", "Direction from the phone compass") else tr("Направление — по вашему перемещению за интервал (нужно идти)", "Direction from your movement over the interval (keep walking)"), style = MaterialTheme.typography.labelMedium)
        Text(tr("Подсказка не реже чем через: ", "Announce at least every: ") + "${iv.toInt()} ${tr("с", "s")}")
        Slider(iv, { iv = it; sp.edit().putInt("interval", it.toInt()).apply() }, valueRange = 5f..300f)
        Text(tr("…или при смещении на: ", "…or after moving: ") + "${dm.toInt()} ${tr("м", "m")}")
        Slider(dm, { dm = it; sp.edit().putInt("dist", it.toInt()).apply() }, valueRange = 5f..100f)
        Text(tr("Голос: ", "Voice: ") + vname.ifBlank { "—" }, style = MaterialTheme.typography.labelMedium)
        Seg(listOf(
            SegItem(tr("Проверить", "Test"), Icons.Default.VolumeUp, enabled = tts != null) { tts?.let { Speaker.sample(it) } },
            SegItem(tr("Выбрать голос", "Choose voice"), Icons.Default.RecordVoiceOver, enabled = tts != null) { chosen = tts?.voice?.name ?: ""; showVoices = true }), fill = true)
        Seg(listOf(
            SegItem(tr("Инструкция", "User guide"), Icons.Default.Info) { Store.showHelp = true },
            SegItem(tr("Статистика трека", "Track stats"), Icons.Default.Timeline) { Store.tab = 4 }), fill = true)
    }
    if (showVoices) tts?.let { x ->
        val list = Speaker.voices(x)
        AlertDialog({ Speaker.pick(x, ctx); showVoices = false }, title = { Text(tr("Выберите голос", "Choose voice")) }, text = {
            if (list.isEmpty()) Text(tr("Голоса не найдены. Установите голоса в настройках синтеза речи телефона.", "No voices found. Install voices in the phone's text-to-speech settings."))
            LazyColumn { itemsIndexed(list) { i, vc ->
                TextButton({ chosen = vc.name; x.voice = vc; Speaker.sample(x) }, Modifier.fillMaxWidth()) {
                    RadioButton(chosen == vc.name, null); Spacer(Modifier.width(8.dp)); Text(Speaker.label(vc, i), Modifier.weight(1f), maxLines = 2)
                }
            } }
        }, confirmButton = {
            TextButton({ if (chosen.isNotBlank()) { Speaker.save(ctx, chosen); vname = chosen }; showVoices = false }) { Text(tr("ОК", "OK")) }
        }, dismissButton = { TextButton({ Speaker.pick(x, ctx); showVoices = false }) { Text(tr("Отмена", "Cancel")) } })
    }
}
data class SegItem(val label: String, val icon: ImageVector? = null, val selected: Boolean = false, val enabled: Boolean = true, val onClick: () -> Unit)

@Composable fun Chip(o: SegItem, mod: Modifier, center: Boolean, icons: Boolean = true) {
    FilterChip(o.selected, o.onClick, {
        Text(o.label, if (center) Modifier.fillMaxWidth() else Modifier, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = if (center) TextAlign.Center else null, style = if (center) MaterialTheme.typography.labelMedium else LocalTextStyle.current)
    }, mod, enabled = o.enabled,
        leadingIcon = if (icons && o.icon != null) { { Icon(o.icon, null, Modifier.size(18.dp)) } }
            else if (!center && o.selected) { { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) } } else null)
}

// fill = true: кнопки на всю ширину равными частями (значки скрываются, если подписи не помещаются)

@Composable fun Seg(opts: List<SegItem>, fill: Boolean = false) {
    if (fill) BoxWithConstraints(Modifier.fillMaxWidth()) {
        val per = (maxWidth - 8.dp * (opts.size - 1)) / opts.size
        val icons = opts.all { (60.0 + it.label.length * 7.5).dp <= per }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { opts.forEach { Chip(it, Modifier.weight(1f), true, icons) } }
    } else FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { opts.forEach { Chip(it, Modifier, false) } }
}

@Composable fun TargetDialog(onDone: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(onDone, title = { Text(tr("Выберите цель", "Choose target")) }, text = {
        if (Store.marks.isEmpty()) Text(tr("Меток пока нет", "No marks yet"))
        LazyColumn { items(Store.marks.reversed(), key = { it.id }) { m ->
            TextButton({ goTo(ctx, m.id); onDone() }, Modifier.fillMaxWidth()) { Text(m.name, Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        } }
    }, confirmButton = { TextButton(onDone) { Text(tr("Закрыть", "Close")) } })
}

fun satText() = if (Store.recording) tr("Спутники: видно ${Store.satVisible}, используется ${Store.satUsed}", "Satellites: ${Store.satVisible} visible, ${Store.satUsed} used")
else tr("GPS выключен — нажмите «Старт»", "GPS off — press Start")

// Запуск GPS и записи; без разрешения на геолокацию сервис не стартует
fun startNav(c: Context) {
    if (c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        Toast.makeText(c, tr("Разрешите доступ к геолокации в настройках телефона", "Allow location access in the phone settings"), Toast.LENGTH_LONG).show(); return
    }
    c.startForegroundService(Intent(c, NavService::class.java))
}

// Выбор цели: включает подсказки и, если GPS выключен, запускает его
fun goTo(c: Context, id: Long) {
    Store.select(id)
    if (!Store.recording) startNav(c)
}

// Ряд кнопок для настроек: помещаются — растягиваются на всю ширину равными частями, нет — листаются свайпом
@Composable fun SegFit(opts: List<SegItem>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val need = opts.sumOf { 62.0 + it.label.length * 8.5 } + 8 * (opts.size - 1)
        if (need.dp <= maxWidth) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { opts.forEach { Chip(it, Modifier.weight(1f), true) } }
        else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { opts.forEach { Chip(it, Modifier, false) } }
    }
}

// Системный список приложений с картами: открыть точку по координатам
fun openMap(c: Context, lat: Double, lon: Double, name: String) {
    val la = String.format(Locale.US, "%.6f", lat); val lo = String.format(Locale.US, "%.6f", lon)
    val i = Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$la,$lo?q=$la,$lo(${Uri.encode(name)})")), tr("Открыть в приложении", "Open with"))
    try { c.startActivity(i) } catch (e: Exception) { Toast.makeText(c, tr("Нет приложений с картами", "No map apps found"), Toast.LENGTH_SHORT).show() }
}
