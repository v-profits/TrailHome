package app.trailhome

import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import kotlin.math.*

class UrlSource(name: String, private val tpl: String, maxZ: Int = 19) : OnlineTileSourceBase(name, 0, maxZ, 256, ".png", arrayOf("https://")) {
    override fun getTileURLString(i: Long): String = tpl.replace("{z}", MapTileIndex.getZoom(i).toString())
        .replace("{x}", MapTileIndex.getX(i).toString()).replace("{y}", MapTileIndex.getY(i).toString())
}

// Плитки Яндекса построены в эллипсоидальной проекции (EPSG:3395), osmdroid рисует сферическую (3857).
// Чтобы метки и трек совпадали с плитками, широта пересчитывается: f — для рисования, inv — для тапов по карте.
object Proj {
    private const val E = 0.0818191908426
    fun on() = MapSources.provider() == "yandex"
    fun f(lat: Double): Double {
        if (!on()) return lat
        val p = Math.toRadians(lat)
        return Math.toDegrees(2 * atan(exp(ln(tan(PI / 4 + p / 2)) - E * atanh(E * sin(p)))) - PI / 2)
    }
    fun inv(lat: Double): Double {
        if (!on()) return lat
        val y = ln(tan(PI / 4 + Math.toRadians(lat) / 2)); var p = Math.toRadians(lat)
        repeat(6) { val s = E * sin(p); p = 2 * atan(exp(y) * ((1 + s) / (1 - s)).pow(E / 2)) - PI / 2 }
        return Math.toDegrees(p)
    }
}

// Источники карт. Адреса плиток можно править здесь. Яндекс и Google — неофициальные адреса плиток.
object MapSources {
    fun provider() = if (Store.provider == "auto") (if (L.en) "google" else "yandex") else Store.provider
    fun key(): String { val p = provider(); val t = Store.mapType; return p + ":" + (if (p == "yandex" && t == "terrain") "normal" else t) }

    private const val YM = "https://core-renderer-tiles.maps.yandex.net/tiles?x={x}&y={y}&z={z}&scale=1&lang=ru_RU&l="
    private const val ESRI = "https://server.arcgisonline.com/ArcGIS/rest/services/"
    private fun g(l: String) = "https://mt1.google.com/vt/lyrs=$l&hl=${if (L.en) "en" else "ru"}&x={x}&y={y}&z={z}"

    fun base(k: String): ITileSource = when (k) {
        "google:satellite" -> UrlSource(k, g("s")); "google:hybrid" -> UrlSource(k, g("y")); "google:terrain" -> UrlSource(k, g("p"), 17)
        "yandex:satellite", "yandex:hybrid" -> UrlSource("yandex:sat", "https://core-sat.maps.yandex.net/tiles?l=sat&x={x}&y={y}&z={z}")
        "yandex:normal" -> UrlSource(k, YM + "map", 20)
        "osm:satellite", "osm:hybrid" -> UrlSource("osm:sat", ESRI + "World_Imagery/MapServer/tile/{z}/{y}/{x}")
        "osm:terrain" -> UrlSource(k, "https://tile.opentopomap.org/{z}/{x}/{y}.png", 17)
        "osm:normal" -> UrlSource(k, "https://tile.openstreetmap.org/{z}/{x}/{y}.png")
        else -> UrlSource(k, g("m"))
    }

    // Прозрачный слой подписей для «Гибридной» у Яндекса и OSM/Esri (у Google гибрид уже готовый)
    fun overlaySource(k: String): ITileSource? = when (k) {
        "yandex:hybrid" -> UrlSource("yandex:labels", YM + "skl", 20)
        "osm:hybrid" -> UrlSource("osm:labels", ESRI + "Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}")
        else -> null
    }
}
