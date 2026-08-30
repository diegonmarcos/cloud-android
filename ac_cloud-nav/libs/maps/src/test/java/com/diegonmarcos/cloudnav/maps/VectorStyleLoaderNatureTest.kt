package com.diegonmarcos.cloudnav.maps

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM test for [VectorStyleLoader.injectNature] — no network. org.json
 * classes are stubbed-to-throw under AGP's default unit-test android.jar, so
 * this module's build.gradle adds a real `org.json:json` jar as a
 * testImplementation dependency; JSONObject/JSONArray work like any plain
 * JVM library here.
 */
class VectorStyleLoaderNatureTest {

    // Minimal OpenMapTiles-shaped style: one vector source, a landcover fill
    // (the layer [VectorStyleLoader.injectNature] reuses the `source` from),
    // plus a water fill / road line / place-label symbol so the "insert
    // before the first line/symbol" positioning logic has something to land
    // in front of.
    private val sampleStyle = """
        {
          "version": 8,
          "sources": { "openmaptiles": { "type": "vector", "url": "https://example.test/tiles.json" } },
          "layers": [
            { "id": "background", "type": "background" },
            { "id": "landcover", "type": "fill", "source": "openmaptiles", "source-layer": "landcover" },
            { "id": "water", "type": "fill", "source": "openmaptiles", "source-layer": "water" },
            { "id": "road", "type": "line", "source": "openmaptiles", "source-layer": "transportation" },
            { "id": "place-label", "type": "symbol", "source": "openmaptiles", "source-layer": "place",
              "layout": { "text-field": "{name}" } }
          ]
        }
    """.trimIndent()

    private val nature = MapStyles.Nature(
        greenColor = "#3fa34d",
        opacity = 0.55,
        classes = listOf("wood", "grass", "park", "forest", "meadow", "grassland"),
        minzoom = 5,
    )

    private fun findLayer(root: JSONObject, id: String): JSONObject? {
        val layers = root.getJSONArray("layers")
        for (i in 0 until layers.length()) {
            val l = layers.getJSONObject(i)
            if (l.optString("id") == id) return l
        }
        return null
    }

    @Test
    fun injectNature_addsGreenFillLayer_fromSameSource_withConfiguredColorAndOpacity() {
        val root = JSONObject(sampleStyle)
        VectorStyleLoader.injectNature(root, nature)

        val added = findLayer(root, "cloudnav-nature-green")
        assertTrue("cloudnav-nature-green layer must be added", added != null)
        assertEquals("fill", added!!.optString("type"))
        assertEquals("openmaptiles", added.optString("source"))
        assertEquals("landcover", added.optString("source-layer"))
        assertEquals(5, added.optInt("minzoom"))

        val paint = added.getJSONObject("paint")
        assertEquals("#3fa34d", paint.optString("fill-color"))
        assertEquals(0.55, paint.optDouble("fill-opacity"), 0.0001)

        // Positioned before the first line (road) — over base fills, under roads/labels.
        val layers = root.getJSONArray("layers")
        var natureIdx = -1
        var roadIdx = -1
        for (i in 0 until layers.length()) {
            when (layers.getJSONObject(i).optString("id")) {
                "cloudnav-nature-green" -> natureIdx = i
                "road" -> roadIdx = i
            }
        }
        assertTrue("nature layer must come before the road line layer", natureIdx in 0 until roadIdx)
    }

    @Test
    fun injectNature_isNoOp_whenNatureConfigAbsent() {
        val root = JSONObject(sampleStyle)
        VectorStyleLoader.injectNature(root, null)
        assertTrue(findLayer(root, "cloudnav-nature-green") == null)
    }

    @Test
    fun injectNature_isIdempotent_secondCallAddsNoDuplicate() {
        val root = JSONObject(sampleStyle)
        VectorStyleLoader.injectNature(root, nature)
        VectorStyleLoader.injectNature(root, nature)

        val layers = root.getJSONArray("layers")
        var count = 0
        for (i in 0 until layers.length()) {
            if (layers.getJSONObject(i).optString("id") == "cloudnav-nature-green") count++
        }
        assertEquals(1, count)
    }
}
