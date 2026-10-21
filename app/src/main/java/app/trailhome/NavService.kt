package app.trailhome

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.*
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.speech.tts.TextToSpeech
import androidx.core.app.ServiceCompat
import java.text.SimpleDateFormat
import java.util.*

class NavService : Service(), LocationListener, SensorEventListener {
    private var tts: TextToSpeech? = null
    private var lastSpeak = 0L
    private var first = true
    private var started = false
    private val h = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { speak(); h.postDelayed(this, 1000) } }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (started) return START_STICKY
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return START_NOT_STICKY
        }
        started = true
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("nav", "Навигация", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "nav").setContentTitle("TrailHome").setContentText(tr("Идёт запись трека и навигация", "Recording track and navigating"))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)).build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        Store.init(this)
        Store.recording = true
        h.post(tick)
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts?.language = if (L.en) Locale.US else Locale("ru") }
        try {
            (getSystemService(LOCATION_SERVICE) as LocationManager).requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 2f, this)
        } catch (e: SecurityException) { stopSelf() }
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR), SensorManager.SENSOR_DELAY_UI)
        return START_STICKY
    }

    override fun onLocationChanged(l: Location) {
        val p = Pt(l.latitude, l.longitude, System.currentTimeMillis())
        Store.loc = p
        if (first) {
            first = false
            Store.addMark(tr("Старт", "Start") + " №" + (Store.marks.count { it.name.startsWith("Старт") || it.name.startsWith("Start") } + 1) + " · " + SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date()), p.lat, p.lon)
            Store.markStart(Store.marks.last().id); Store.select(-1)
        }
        Store.track.add(p)
        if (Store.track.size % 10 == 0) Store.saveTrack()
        speak()
    }

    private fun speak() {
        val sp = getSharedPreferences("s", MODE_PRIVATE)
        if (!Store.guiding || !sp.getBoolean("voice", true)) { lastSpeak = 0; return }
        val t = Store.target() ?: return
        val me = Store.loc ?: return
        val now = System.currentTimeMillis()
        if (now - lastSpeak < sp.getInt("interval", 15) * 1000L) return
        lastSpeak = now
        val arrived = Geo.dist(me.lat, me.lon, t.lat, t.lon) < 15
        tts?.speak(if (arrived) tr("На месте", "Arrived") else turnText(Geo.rel(me, t)), TextToSpeech.QUEUE_FLUSH, null, "n")
        if (arrived) Store.guiding = false
    }

    override fun onSensorChanged(e: SensorEvent) {
        val r = FloatArray(9); val o = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(r, e.values)
        SensorManager.getOrientation(r, o)
        Store.azimuth = ((Math.toDegrees(o[0].toDouble()) + 360) % 360).toFloat()
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onDestroy() {
        (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(this)
        (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(this)
        h.removeCallbacks(tick); tts?.shutdown(); Store.saveTrack(); Store.recording = false
        super.onDestroy()
    }
}
