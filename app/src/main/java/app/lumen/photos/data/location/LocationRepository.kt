package app.lumen.photos.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import app.lumen.photos.data.db.LocationDao
import app.lumen.photos.data.db.MediaLocationEntity
import app.lumen.photos.data.media.MediaItem
import app.lumen.photos.data.media.MediaRepository
import app.lumen.photos.search.PlaceCandidate
import app.lumen.photos.search.PlaceLevel
import app.lumen.photos.search.PlaceRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A place that occurs in the library, with the number of photos taken there. */
data class LibraryPlace(val ref: PlaceRef, val count: Int, val names: List<String>, val parent: String?)

data class LocationScan(val done: Int, val total: Int)

/**
 * Reads the GPS position of every photo and video once (EXIF / video metadata, kept in Room) and
 * maps it to the nearest town of the offline [PlaceDirectory]. Feeds the "Ort" search filter.
 */
class LocationRepository(
    private val context: Context,
    private val dao: LocationDao,
    private val media: MediaRepository,
    private val scope: CoroutineScope,
) {
    val directory = PlaceDirectory(context)
    private val mutex = Mutex()

    /** mediaId -> index of the nearest town. */
    private val _cityOf = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val cityOf: StateFlow<Map<Long, Int>> = _cityOf.asStateFlow()

    private val _places = MutableStateFlow<List<LibraryPlace>>(emptyList())
    val places: StateFlow<List<LibraryPlace>> = _places.asStateFlow()

    private val _scan = MutableStateFlow<LocationScan?>(null)
    val scan: StateFlow<LocationScan?> = _scan.asStateFlow()

    private val _withGps = MutableStateFlow(0)
    val withGps: StateFlow<Int> = _withGps.asStateFlow()

    /** Without "Zugriff auf Standort in Medien" Android removes the GPS data from every photo. */
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

    @OptIn(FlowPreview::class)
    fun start() {
        media.version.debounce(4_000).onEach { refresh() }.launchIn(scope)
    }

    fun refresh() {
        scope.launch { runCatching { scan() } }
    }

    private suspend fun scan() = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!media.loaded.value) return@withContext
            directory.load()
            val items = media.media.value
            val known = dao.all().associateBy { it.mediaId }
            publish(known.values, items)
            if (!hasPermission()) return@withContext
            val present = items.mapTo(HashSet()) { it.id }
            known.keys.filter { it !in present }.chunked(500).forEach { dao.delete(it) }
            val todo = items.filter { known[it.id]?.dateModified != it.dateModified }
            if (todo.isEmpty()) return@withContext
            val all = HashMap(known)
            var done = 0
            _scan.value = LocationScan(0, todo.size)
            for (chunk in todo.chunked(96)) {
                val rows = chunk.chunked(32).map { part ->
                    async { part.map { item -> read(item) } }
                }.awaitAll().flatten()
                dao.upsert(rows)
                rows.forEach { all[it.mediaId] = it }
                done += chunk.size
                _scan.value = LocationScan(done, todo.size)
                if (done % 960 == 0 || done == todo.size) publish(all.values, items)
            }
            _scan.value = null
        }
    }

    private fun read(item: MediaItem): MediaLocationEntity {
        val latLon: DoubleArray? = runCatching {
            val uri = runCatching { MediaStore.setRequireOriginal(item.uri) }.getOrDefault(item.uri)
            if (item.isVideo) {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(context, uri)
                    parseIso6709(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION))
                }
            } else {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd -> ExifInterface(pfd.fileDescriptor).latLong }
            }
        }.getOrNull()?.takeIf { it.size == 2 && !(it[0] == 0.0 && it[1] == 0.0) && it[0] in -90.0..90.0 && it[1] in -180.0..180.0 }
        return MediaLocationEntity(item.id, item.dateModified, latLon?.get(0), latLon?.get(1))
    }

    /** Rebuilds the media -> town map and the list of places with their photo counts. */
    private fun publish(rows: Collection<MediaLocationEntity>, items: List<MediaItem>) {
        val present = items.mapTo(HashSet()) { it.id }
        val map = HashMap<Long, Int>()
        for (r in rows) {
            if (r.mediaId !in present) continue
            val lat = r.lat ?: continue
            val lon = r.lon ?: continue
            directory.nearest(lat, lon)?.let { map[r.mediaId] = it.index }
        }
        _cityOf.value = map
        _withGps.value = map.size

        val byCity = map.values.groupingBy { it }.eachCount()
        val byRegion = HashMap<String, Int>()
        val byCountry = HashMap<String, Int>()
        for ((index, n) in byCity) {
            val city = directory.city(index) ?: continue
            byRegion.merge(city.region, n, Int::plus)
            byCountry.merge(city.country, n, Int::plus)
        }
        val list = ArrayList<LibraryPlace>()
        for ((code, n) in byCountry) {
            val name = directory.countryName(code)
            list += LibraryPlace(PlaceRef(PlaceLevel.COUNTRY, code, name), n, directory.countryNames(code), null)
        }
        for ((key, n) in byRegion) {
            val name = directory.regionName(key) ?: continue
            val country = directory.countryName(key.substringBefore('.'))
            list += LibraryPlace(PlaceRef(PlaceLevel.REGION, key, name), n, listOf(name), country)
        }
        for ((index, n) in byCity) {
            val city = directory.city(index) ?: continue
            val parent = listOfNotNull(directory.regionName(city.region), directory.countryName(city.country)).joinToString(", ")
            list += LibraryPlace(PlaceRef(PlaceLevel.CITY, index.toString(), city.name), n, listOf(city.name) + city.otherNames, parent)
        }
        _places.value = list.sortedByDescending { it.count }
    }

    /** True if [mediaId] was taken at [place]. */
    fun matches(mediaId: Long, place: PlaceRef, cityOf: Map<Long, Int>): Boolean {
        val city = cityOf[mediaId]?.let { directory.city(it) } ?: return false
        return when (place.level) {
            PlaceLevel.CITY -> city.index.toString() == place.key
            PlaceLevel.REGION -> city.region == place.key
            PlaceLevel.COUNTRY -> city.country == place.key
        }
    }

    /** "München, Bayern, Deutschland" for the info sheet. */
    fun describe(mediaId: Long): String? {
        val city = _cityOf.value[mediaId]?.let { directory.city(it) } ?: return null
        return listOfNotNull(city.name, directory.regionName(city.region), directory.countryName(city.country)).distinct().joinToString(", ")
    }

    fun candidates(): List<PlaceCandidate> = _places.value.map { PlaceCandidate(it.ref, it.names) }

    companion object {
        /** "+52.5200+013.4050/" (ISO 6709, as in MP4 metadata) -> [lat, lon]. */
        fun parseIso6709(value: String?): DoubleArray? {
            if (value.isNullOrBlank()) return null
            val m = Regex("([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)").find(value) ?: return null
            val lat = m.groupValues[1].toDoubleOrNull() ?: return null
            val lon = m.groupValues[2].toDoubleOrNull() ?: return null
            return doubleArrayOf(lat, lon)
        }
    }
}
