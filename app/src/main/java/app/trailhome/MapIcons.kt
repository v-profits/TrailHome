package app.trailhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

// Значки карты: метка — оранжевая, старт — фиолетовая с домиком, цель — крупнее с жёлтым ореолом, вы — синяя точка с ореолом.
class MapIcons(c: Context) {
    private val m0 = makePin(c, 0xFFFB8C00.toInt(), 0, false)
    private val m1 = makePin(c, 0xFFFB8C00.toInt(), 0, true)
    private val s0 = makePin(c, 0xFF5E35B1.toInt(), 1, false)
    private val s1 = makePin(c, 0xFF5E35B1.toInt(), 1, true)
    val me = makeMe(c)
    fun pin(start: Boolean, target: Boolean) = if (start) (if (target) s1 else s0) else (if (target) m1 else m0)
}

fun makePin(c: Context, color: Int, glyph: Int, target: Boolean): Drawable {
    val d = c.resources.displayMetrics.density
    val w = ((if (target) 46f else 34f) * d).toInt(); val h = (w * 1.3f).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val cv = Canvas(bmp)
    val pad = (if (target) 9f else 5f) * d
    val r = w / 2f - pad; val cx = w / 2f; val cy = pad + r
    val path = Path().apply {
        addCircle(cx, cy, r, Path.Direction.CW)
        moveTo(cx + r * .8f, cy + r * .55f); lineTo(cx, h - pad); lineTo(cx - r * .8f, cy + r * .55f); close()
    }
    val p = Paint(Paint.ANTI_ALIAS_FLAG); p.strokeJoin = Paint.Join.ROUND; p.style = Paint.Style.STROKE
    if (target) { p.color = 0xFFFFEA00.toInt(); p.strokeWidth = 16f * d; cv.drawPath(path, p) }
    p.color = 0xFF1A1A1A.toInt(); p.strokeWidth = 8f * d; cv.drawPath(path, p)
    p.color = 0xFFFFFFFF.toInt(); p.strokeWidth = 4f * d; cv.drawPath(path, p)
    p.style = Paint.Style.FILL; p.color = color; cv.drawPath(path, p)
    p.color = 0xFFFFFFFF.toInt()
    if (glyph == 0) cv.drawCircle(cx, cy, r * .38f, p)
    else {
        val g = Path().apply {
            moveTo(cx - r * .6f, cy - r * .02f); lineTo(cx, cy - r * .6f); lineTo(cx + r * .6f, cy - r * .02f); close()
            addRect(cx - r * .42f, cy - r * .02f, cx + r * .42f, cy + r * .5f, Path.Direction.CW)
        }
        cv.drawPath(g, p)
    }
    return BitmapDrawable(c.resources, bmp)
}

fun makeMe(c: Context): Drawable {
    val d = c.resources.displayMetrics.density
    val s = (44 * d).toInt(); val cx = s / 2f
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val cv = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = 0x401E88E5; cv.drawCircle(cx, cx, 20f * d, p)
    p.color = 0xFF0B2A57.toInt(); cv.drawCircle(cx, cx, 11.5f * d, p)
    p.color = 0xFFFFFFFF.toInt(); cv.drawCircle(cx, cx, 10f * d, p)
    p.color = 0xFF1E88E5.toInt(); cv.drawCircle(cx, cx, 7.5f * d, p)
    return BitmapDrawable(c.resources, bmp)
}
