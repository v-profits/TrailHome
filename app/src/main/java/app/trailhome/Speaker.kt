package app.trailhome

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

object Speaker {
    fun locale(): Locale = if (L.en) Locale.US else Locale("ru", "RU")

    // Установленные голоса языка: сначала офлайн, затем по качеству
    fun voices(t: TextToSpeech): List<Voice> {
        val lang = locale().language
        return (t.voices ?: emptySet<Voice>())
            .filter { it.locale.language == lang && it.features?.contains("notInstalled") != true }
            .sortedWith(compareBy<Voice>({ it.isNetworkConnectionRequired }, { -it.quality }, { it.name }))
    }

    fun pick(t: TextToSpeech, c: Context) {
        t.language = locale(); t.setSpeechRate(0.95f); t.setPitch(1.0f)
        val saved = c.getSharedPreferences("s", 0).getString("voice_" + locale().language, null)
        val vs = voices(t)
        (vs.firstOrNull { it.name == saved } ?: vs.firstOrNull())?.let { t.voice = it }
    }

    fun next(t: TextToSpeech, c: Context): String {
        val vs = voices(t)
        if (vs.isEmpty()) return ""
        val v = vs[(vs.indexOfFirst { it.name == t.voice?.name } + 1) % vs.size]
        t.voice = v
        c.getSharedPreferences("s", 0).edit().putString("voice_" + locale().language, v.name).apply()
        return v.name
    }

    fun save(c: Context, name: String) { c.getSharedPreferences("s", 0).edit().putString("voice_" + locale().language, name).apply() }

    fun label(v: Voice, i: Int) = "${i + 1}. " + v.name + " · " + (if (v.isNetworkConnectionRequired) tr("онлайн", "online") else tr("офлайн", "offline"))

    fun sample(t: TextToSpeech) { t.speak(turnSpeech(30.0, 450.0), TextToSpeech.QUEUE_FLUSH, null, "t") }
}
