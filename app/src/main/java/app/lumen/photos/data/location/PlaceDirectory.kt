package app.lumen.photos.data.location

import android.content.Context
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.math.cos
import kotlin.math.floor

/**
 * Offline place names (GeoNames, CC BY 4.0): about 64,000 towns with at least 5,000 inhabitants,
 * with German names where known, their regions (Bundesland, state …) and countries. Used to turn
 * the GPS position of a photo into "München · Bayern · Deutschland" without any network service.
 */
class PlaceDirectory(private val context: Context) {

    class City(
        val index: Int,
        val name: String,
        /** Other spellings to search by, e.g. "Munich" for "München". */
        val otherNames: List<String>,
        val lat: Float,
        val lon: Float,
        val country: String,
        /** Region key "DE.02". */
        val region: String,
    )

    @Volatile private var cities: List<City> = emptyList()
    @Volatile private var regions: Map<String, String> = emptyMap()
    @Volatile private var grid: Map<Int, IntArray> = emptyMap()
    @Volatile var loaded = false
        private set

    /** Reads the asset once (~150 ms). Call off the main thread. */
    @Synchronized
    fun load() {
        if (loaded) return
        val list = ArrayList<City>(65_000)
        val regionNames = HashMap<String, String>(4_000)
        GZIPInputStream(context.assets.open(ASSET)).bufferedReader().useLines { lines ->
            for (line in lines) {
                val c = line.split('\t')
                when (c[0]) {
                    "R" -> if (c.size >= 3) regionNames[c[1]] = c[2]
                    "C" -> if (c.size >= 8) list += City(
                        index = list.size,
                        name = c[6],
                        otherNames = c[7].split('|').filter { it.isNotEmpty() },
                        lat = c[1].toFloat(),
                        lon = c[2].toFloat(),
                        country = c[3],
                        region = "${c[3]}.${c[4]}",
                    )
                }
            }
        }
        val cells = HashMap<Int, MutableList<Int>>()
        for (city in list) cells.getOrPut(cell(city.lat.toDouble(), city.lon.toDouble())) { ArrayList(4) }.add(city.index)
        cities = list
        regions = regionNames
        grid = cells.mapValues { it.value.toIntArray() }
        loaded = true
    }

    fun city(index: Int): City? = cities.getOrNull(index)

    fun regionName(key: String): String? = regions[key]

    fun countryName(code: String): String = Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.GERMANY).ifBlank { code }

    /** Other names a country can be searched by (English, local). */
    fun countryNames(code: String): List<String> {
        val locale = Locale.Builder().setRegion(code).build()
        return listOf(locale.getDisplayCountry(Locale.GERMANY), locale.getDisplayCountry(Locale.ENGLISH)).filter { it.isNotBlank() }.distinct()
    }

    /** The town nearest to the position, if one is within [maxKm]. */
    fun nearest(lat: Double, lon: Double, maxKm: Double = 30.0): City? {
        if (!loaded) return null
        val la = floor(lat).toInt()
        val lo = floor(lon).toInt()
        val kx = cos(Math.toRadians(lat))
        var best: City? = null
        var bestD = maxKm * maxKm
        for (dy in -1..1) for (dx in -1..1) {
            val ids = grid[key(la + dy, wrap(lo + dx))] ?: continue
            for (i in ids) {
                val c = cities[i]
                val y = (c.lat - lat) * KM_PER_DEG
                var dLon = c.lon - lon
                if (dLon > 180) dLon -= 360.0
                if (dLon < -180) dLon += 360.0
                val x = dLon * KM_PER_DEG * kx
                val d = x * x + y * y
                if (d < bestD) { bestD = d; best = c }
            }
        }
        return best
    }

    private fun cell(lat: Double, lon: Double) = key(floor(lat).toInt(), floor(lon).toInt())
    private fun key(la: Int, lo: Int) = (la + 90) * 1000 + (lo + 180)
    private fun wrap(lo: Int) = when {
        lo < -180 -> lo + 360
        lo >= 180 -> lo - 360
        else -> lo
    }

    companion object {
        const val ASSET = "places.tsv.gz"
        private const val KM_PER_DEG = 111.2
    }
}
