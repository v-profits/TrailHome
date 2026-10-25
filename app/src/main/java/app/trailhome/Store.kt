package app.trailhome

import android.content.Context
import androidx.compose.runtime.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

data class Mark(val id: Long, val name: String, val lat: Double, val lon: Double, val note: String = "", val time: Long = System.currentTimeMillis())
data class Pt(val lat: Double, val lon: Double, val t: Long, val trip: Long = 0)

object Store {
    val marks = mutableStateListOf<Mark>()
    val track = mutableStateListOf<Pt>()
    var loc by mutableStateOf<Pt?>(null)
    var azimuth by mutableFloatStateOf(0f)
    var targetId by mutableLongStateOf(-1L)
    var theme by mutableStateOf("system")
    var tab by mutableIntStateOf(0)
    var focus by mutableStateOf<Mark?>(null)
    var sp: android.content.SharedPreferences? = null
    var guiding by mutableStateOf(false)
    var gpsOn by mutableStateOf(false)        // служба GPS работает
    var autoGps = false                       // GPS включён автоматически (Старт или выбор цели)
    var showHistory by mutableStateOf(false)  // показывать линии всех маршрутов
    var viewTrip by mutableLongStateOf(-1L)   // маршрут, выбранный в окне метки или старта
    var pin by mutableStateOf<Mark?>(null)    // метка, выделенная мишенью на карте
    fun switchHistory(on: Boolean) { showHistory = on; sp?.edit()?.putBoolean("history", on)?.apply() }
    // «Стоп» горит, пока идёт запись, подсказки или GPS, включённый автоматически
    val busy: Boolean get() = recording || guiding || (gpsOn && autoGps)
    // Какие маршруты рисовать: при скрытой истории — запись от нажатого «Старт», маршрут старта-цели и выбранный в окне метки
    fun visibleTrips(): Set<Long> = if (showHistory) track.map { it.trip }.toSet() else setOfNotNull(
        if (recording && startId != -1L) startId else null,
        target()?.takeIf { isStart(it) }?.id,
        viewTrip.takeIf { it != -1L })
    // Маршрут (поездка) метки: у старта — его собственный, у обычной метки — тот, на время которого она создана
    fun tripOf(m: Mark): Long? {
        if (track.any { it.trip == m.id }) return m.id
        return track.groupBy { it.trip }.filterKeys { it != 0L }.entries
            .firstOrNull { (_, p) -> m.time >= p.first().t - 60000 && m.time <= p.last().t + 60000 }?.key
    }
    var compass by mutableStateOf(true)
    var satVisible by mutableIntStateOf(0)
    var satUsed by mutableIntStateOf(0)
    fun switchCompass(on: Boolean) { compass = on; sp?.edit()?.putBoolean("compass", on)?.apply() }
    var provider by mutableStateOf("google")
    var mapType by mutableStateOf("normal")
    // Направление движения: по компасу или по перемещению за интервал (секунды из настроек)
    fun curHeading(me: Pt): Double? {
        if (compass) return azimuth.toDouble()
        val iv = (sp?.getInt("interval", 30) ?: 30) * 1000L
        val ref = track.lastOrNull { me.t - it.t >= iv } ?: track.firstOrNull { me.t - it.t <= iv } ?: return null
        return if (Geo.dist(ref.lat, ref.lon, me.lat, me.lon) < 5) null else Geo.bearing(ref.lat, ref.lon, me.lat, me.lon)
    }
    var startId by mutableLongStateOf(-1L)
    var showHelp by mutableStateOf(false)
    fun select(id: Long) { targetId = id; guiding = id != -1L; sp?.edit()?.putLong("target", id)?.apply() }
    fun markStart(id: Long) { startId = id; sp?.edit()?.putLong("start", id)?.apply() }
    fun startMark() = marks.firstOrNull { it.id == startId }
    fun isStart(m: Mark) = m.id == startId || m.name.startsWith("Старт") || m.name.startsWith("Start")
    fun updateMark(m: Mark) { val i = marks.indexOfFirst { it.id == m.id }; if (i >= 0) { marks[i] = m; saveMarks() } }
    var recording by mutableStateOf(false)
    private var dir: File? = null

    fun target() = marks.firstOrNull { it.id == targetId }

    fun init(c: Context) {
        if (dir != null) return
        dir = c.filesDir
        sp = c.getSharedPreferences("s", 0)
        L.mode = sp!!.getString("lang", "system")!!; theme = sp!!.getString("theme", "system")!!; targetId = sp!!.getLong("target", -1L); startId = sp!!.getLong("start", -1L); showHistory = sp!!.getBoolean("history", false); compass = sp!!.getBoolean("compass", true); provider = sp!!.getString("provider", "google")!!; mapType = sp!!.getString("maptype", "normal")!!
        runCatching {
            val a = JSONArray(File(dir, "marks.json").readText())
            for (i in 0 until a.length()) a.getJSONObject(i).let {
                marks.add(Mark(it.getLong("id"), it.getString("n"), it.getDouble("la"), it.getDouble("lo"), it.optString("note"), it.getLong("t")))
            }
        }
        runCatching {
            val a = JSONArray(File(dir, "track.json").readText())
            for (i in 0 until a.length()) a.getJSONArray(i).let { track.add(Pt(it.getDouble(0), it.getDouble(1), it.getLong(2), if (it.length() > 3) it.getLong(3) else 0L)) }
        }
    }

    fun saveMarks() {
        val a = JSONArray()
        marks.forEach { a.put(JSONObject().put("id", it.id).put("n", it.name).put("la", it.lat).put("lo", it.lon).put("note", it.note).put("t", it.time)) }
        runCatching { File(dir, "marks.json").writeText(a.toString()) }
    }

    fun saveTrack() {
        val a = JSONArray()
        track.toList().forEach { a.put(JSONArray().put(it.lat).put(it.lon).put(it.t).put(it.trip)) }
        runCatching { File(dir, "track.json").writeText(a.toString()) }
    }

    fun addMark(name: String, lat: Double, lon: Double, note: String = "") {
        marks.add(Mark(System.nanoTime(), name, lat, lon, note)); saveMarks()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun unesc(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    fun toGpx(): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><gpx version=\"1.1\" creator=\"LesNavigator\" xmlns=\"http://www.topografix.com/GPX/1/1\">")
            marks.filter { !isStart(it) }.forEach { append("<wpt lat=\"${it.lat}\" lon=\"${it.lon}\"><name>${esc(it.name)}</name><desc>${esc(it.note)}</desc></wpt>") }
            append("</gpx>")
        }
    }

    fun fromGpx(s: String): Int {
        var n = 0
        Regex("<wpt([^>]*)>(.*?)</wpt>", RegexOption.DOT_MATCHES_ALL).findAll(s).forEach { m ->
            fun attr(k: String) = Regex("$k=[\"']([-\\d.]+)[\"']").find(m.groupValues[1])?.groupValues?.get(1)?.toDoubleOrNull()
            fun tag(t: String) = Regex("<$t>(.*?)</$t>", RegexOption.DOT_MATCHES_ALL).find(m.groupValues[2])?.groupValues?.get(1)?.let(::unesc) ?: ""
            val la = attr("lat"); val lo = attr("lon")
            if (la != null && lo != null) { addMark(tag("name").ifBlank { "Метка" }, la, lo, tag("desc")); n++ }
        }
        return n
    }
}

object Geo {
    private fun rad(d: Double) = Math.toRadians(d)
    fun dist(a: Double, b: Double, c: Double, d: Double): Double {
        val p = rad(c - a); val q = rad(d - b)
        val x = sin(p / 2).pow(2) + cos(rad(a)) * cos(rad(c)) * sin(q / 2).pow(2)
        return 2 * 6371000.0 * asin(sqrt(x))
    }
    fun bearing(a: Double, b: Double, c: Double, d: Double): Double {
        val dl = rad(d - b)
        val y = sin(dl) * cos(rad(c))
        val x = cos(rad(a)) * sin(rad(c)) - sin(rad(a)) * cos(rad(c)) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }
    fun rel(me: Pt, t: Mark, h: Double = Store.azimuth.toDouble()) = (bearing(me.lat, me.lon, t.lat, t.lon) - h + 720) % 360
    fun clock(rel: Double): String {
        val h = ((rel + 15) / 30).toInt() % 12
        return if (h == 0) tr("прямо", "straight ahead") else if (L.en) "at $h o'clock" else "на $h ${if (h == 1) "час" else if (h < 5) "часа" else "часов"}"
    }
    fun fmt(d: Double) = if (d < 1000) "${d.toInt()} ${tr("м", "m")}" else "%.1f ${tr("км", "km")}".format(d / 1000)
}

object L {
    var mode by mutableStateOf("system")
    val en get() = mode == "en" || (mode == "system" && Locale.getDefault().language != "ru")
}
fun tr(ru: String, en: String) = if (L.en) en else ru

private fun decl(n: Int, one: String, few: String, many: String) = if (n % 100 in 11..14) many else when (n % 10) { 1 -> one; 2, 3, 4 -> few; else -> many }

// Подсказка простыми словами: >135° — развернитесь, 45–135° — поверните, <45° — держитесь правее/левее на N°
fun turnText(rel: Double, speech: Boolean = false): String {
    val d = if (rel > 180) 360 - rel else rel
    val n = (d / 5).roundToInt() * 5
    val right = rel <= 180
    return when {
        d > 135 -> tr("Развернитесь", "Turn around")
        d > 45 -> if (right) tr("Поверните направо", "Turn right") else tr("Поверните налево", "Turn left")
        d < 10 -> tr("Идите прямо", "Go straight")
        else -> (if (right) (if (speech) tr("Держитесь правее", "Bear right") else tr("Правее", "Bear right")) else (if (speech) tr("Держитесь левее", "Bear left") else tr("Левее", "Bear left"))) + tr(" на ", " by ") + "$n°"
    }
}

// То же для голоса: слова вместо символов и расстояние до цели
fun turnSpeech(rel: Double, dist: Double): String {
    val step = if (dist < 100) 5 else 10
    val m = max(step, (dist / step).roundToInt() * step)
    val far = if (dist < 1000) "$m " + (if (L.en) "meters" else decl(m, "метр", "метра", "метров"))
    else {
        val k = (dist / 100).roundToInt() / 10.0
        val ks = if (k == floor(k)) "${k.toInt()}" else String.format(Locale.US, "%.1f", k)
        ks + " " + (if (L.en) "kilometers" else if (k == floor(k)) decl(k.toInt(), "километр", "километра", "километров") else "километра")
    }
    return turnText(rel, true).replace("°", " " + tr("градусов", "degrees")) + ", " + tr("до цели ", "to the target ") + (if (L.en) far else far.replace('.', ','))
}
