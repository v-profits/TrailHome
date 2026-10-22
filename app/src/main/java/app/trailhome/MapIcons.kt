package app.trailhome

import android.content.Context
import androidx.core.content.ContextCompat

// Значки берутся из ресурсов: res/drawable/ic_pin_mark.xml, ic_pin_start.xml, ic_pin_mark_target.xml,
// ic_pin_start_target.xml, ic_me.xml; цвета — res/values/map_colors.xml. Меняйте их, код трогать не нужно.
class MapIcons(private val c: Context) {
    private fun d(id: Int) = ContextCompat.getDrawable(c, id)!!
    private val m0 = d(R.drawable.ic_pin_mark)
    private val m1 = d(R.drawable.ic_pin_mark_target)
    private val s0 = d(R.drawable.ic_pin_start)
    private val s1 = d(R.drawable.ic_pin_start_target)
    val me = d(R.drawable.ic_me)
    fun pin(start: Boolean, target: Boolean) = if (start) (if (target) s1 else s0) else (if (target) m1 else m0)
}
