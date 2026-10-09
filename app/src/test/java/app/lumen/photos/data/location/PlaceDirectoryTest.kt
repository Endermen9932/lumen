package app.lumen.photos.data.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlaceDirectoryTest {
    private val dir = PlaceDirectory { File("src/main/assets/${PlaceDirectory.ASSET}").inputStream() }.apply { load() }

    @Test fun germanNamesRegionsAndCountries() {
        val munich = dir.nearest(48.1374, 11.5755)!!
        assertEquals("München", munich.name)
        assertTrue("Munich" in munich.otherNames)
        assertEquals("Bayern", dir.regionName(munich.region))
        assertEquals("Deutschland", dir.countryName(munich.country))
        assertEquals("Rom", dir.nearest(41.9, 12.49)!!.name)
        assertEquals("Wien", dir.nearest(48.2, 16.37)!!.name)
    }

    @Test fun nothingFarFromAnyTown() {
        assertNull(dir.nearest(0.0, -30.0))
    }

    @Test fun worksAcrossTheDateLine() {
        // Suva, Fiji (178.4° E) from a point just east of 180°.
        assertTrue(dir.nearest(-18.14, 178.44) != null)
    }
}
