package com.semenenko.trailhome

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.*
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.ServiceCompat
import java.text.SimpleDateFormat
import java.util.*

class NavService : Service(), LocationListener, SensorEventListener {
    private var tts: TextToSpeech? = null
    private var lastSpeak = 0L
    private var lastPos: Pt? = null
    private val gnss = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(s: GnssStatus) {
            var u = 0
            for (i in 0 until s.satelliteCount) if (s.usedInFix(i)) u++
            Store.satVisible = s.satelliteCount; Store.satUsed = u
        }
    }
    private var first = true
    private var started = false
    private val h = android.os.Handler(android.os.Looper.getMainLooper())
    private var nTick = 0
    private var newTrip = false
    private var wasRec = false
    private var fixed = false
    private val tick = object : Runnable { override fun run() { tripTick(); speak(); if (++nTick % 5 == 0) (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(1, notif()); h.postDelayed(this, 1000) } }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        if (started) return START_STICKY
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return START_NOT_STICKY
        }
        started = true
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("nav", "Навигация", NotificationManager.IMPORTANCE_LOW))
        val n = notif()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        Store.init(this)
        Store.gpsOn = true
        h.post(tick)
        if (!Store.guiding) Store.select(-1)   // цель, выбранная до старта, сохраняется; иначе — тишина и без цели
        tts = TextToSpeech(this) {
            if (it == TextToSpeech.SUCCESS) tts?.let { t ->
                Speaker.pick(t, this)
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { if (id == "arrive") h.post { finishTrip() } }
                    @Deprecated("Deprecated in Java") override fun onError(id: String?) { if (id == "arrive") h.post { finishTrip() } }
                })
            }
        }
        try {
            (getSystemService(LOCATION_SERVICE) as LocationManager).requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 2f, this)
        } catch (e: SecurityException) { stopSelf() }
        try { (getSystemService(LOCATION_SERVICE) as LocationManager).registerGnssStatusCallback(gnss, h) } catch (e: SecurityException) {}
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR), SensorManager.SENSOR_DELAY_UI)
        return START_STICKY
    }

    // Уведомление в шторке висит, пока работает GPS; показывает цель и расстояние, есть кнопка «Стоп»
    private fun notif(): Notification {
        val t = Store.target(); val me = Store.loc
        val text = (if (Store.guiding && t != null && me != null) tr("Ведёт к цели: ", "Guiding to: ") + t.name + " · " + Geo.fmt(Geo.dist(me.lat, me.lon, t.lat, t.lon))
            else if (Store.recording) tr("Запись маршрута", "Recording route") else tr("GPS работает", "GPS is on")) + " · " + tr("спутники ", "satellites ") + "${Store.satUsed}/${Store.satVisible}"
        return Notification.Builder(this, "nav").setContentTitle(tr("TrailHome: GPS включён", "TrailHome: GPS on")).setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, tr("Стоп", "Stop"),
                PendingIntent.getService(this, 1, Intent(this, NavService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)).build())
            .build()
    }

    // Старт маршрута: метка «Старт №N …» в первой точке записи
    private fun makeStart(p: Pt) {
        val k = Store.marks.count { it.name.startsWith("Старт") || it.name.startsWith("Start") } + 1
        Store.addMark(tr("Старт", "Start") + " №" + k + " · " + SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date()), p.lat, p.lon)
        Store.markStart(Store.marks.last().id)
    }

    // Следим за кнопкой «Старт/Стоп»: запись включается и выключается отдельно от GPS
    private fun tripTick() {
        val r = Store.recording
        if (r && !wasRec) {
            val p = Store.loc
            if (fixed && p != null) { makeStart(p); Store.track.add(p.copy(trip = Store.startId)) } else newTrip = true
        }
        if (!r && wasRec) { newTrip = false; Store.saveTrack() }
        wasRec = r
    }

    // Конец поездки у стартовой точки: запись и цель сбрасываются, GPS выключается, если его включили автоматически
    private fun finishTrip() {
        Store.recording = false; Store.select(-1)
        if (Store.autoGps) { Store.autoGps = false; stopSelf() }
    }

    override fun onLocationChanged(l: Location) {
        val p = Pt(l.latitude, l.longitude, System.currentTimeMillis())
        Store.loc = p; fixed = true
        if (Store.recording) {
            if (newTrip) { newTrip = false; makeStart(p) }
            Store.track.add(p.copy(trip = Store.startId))
            if (Store.track.size % 10 == 0) Store.saveTrack()
        }
        speak()
    }

    private fun speak() {
        val sp = getSharedPreferences("s", MODE_PRIVATE)
        if (!Store.guiding) { lastSpeak = 0; lastPos = null; return }
        val t = Store.target() ?: return
        val me = Store.loc ?: return
        val voice = sp.getBoolean("voice", true)
        if (Geo.dist(me.lat, me.lon, t.lat, t.lon) < 15) {
            Store.guiding = false
            // у стартовой точки запись останавливается сама (если вы от неё отходили)
            val home = Store.isStart(t) && Store.track.any { Geo.dist(it.lat, it.lon, t.lat, t.lon) > 50 }
            if (voice) tts?.speak(tr("На месте", "Arrived"), TextToSpeech.QUEUE_FLUSH, null, if (home) "arrive" else "n")
            if (home) h.postDelayed({ finishTrip() }, if (voice) 6000L else 0L)
            return
        }
        if (!voice) return
        val now = System.currentTimeMillis()
        val lp = lastPos
        // подсказка: через N секунд ИЛИ когда сместились на M метров от места прошлой подсказки
        val due = now - lastSpeak >= sp.getInt("interval", 30) * 1000L || (lp != null && Geo.dist(lp.lat, lp.lon, me.lat, me.lon) >= sp.getInt("dist", 15))
        if (!due) return
        val hd = Store.curHeading(me) ?: return
        lastSpeak = now; lastPos = me
        tts?.speak(turnSpeech(Geo.rel(me, t, hd), Geo.dist(me.lat, me.lon, t.lat, t.lon)), TextToSpeech.QUEUE_FLUSH, null, "n")
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
        (getSystemService(LOCATION_SERVICE) as LocationManager).unregisterGnssStatusCallback(gnss); Store.satVisible = 0; Store.satUsed = 0
        (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(this)
        h.removeCallbacks(tick); tts?.shutdown(); Store.saveTrack(); Store.recording = false; Store.gpsOn = false; Store.autoGps = false; Store.select(-1); Store.select(-1)
        super.onDestroy()
    }
}
