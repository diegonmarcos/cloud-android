package com.diegonmarcos.superapp.kdeconnect

import android.util.Base64
import org.json.JSONObject

/**
 * Read-only view of the baked wg mesh topology (data/mesh.json →
 * BuildConfig.MESH_JSON_B64). Used by the Probe section to ping every
 * declared peer over wg0. Self-contained — no dependency on the app module.
 */
object KdeMesh {
    data class Node(val name: String, val wgIp: String, val role: String)

    @Volatile private var cached: List<Node>? = null

    fun nodes(): List<Node> {
        cached?.let { return it }
        val out = mutableListOf<Node>()
        runCatching {
            val root = JSONObject(String(Base64.decode(BuildConfig.MESH_JSON_B64, Base64.NO_WRAP)))
            val arr = root.optJSONArray("nodes") ?: org.json.JSONArray()
            for (i in 0 until arr.length()) {
                val n = arr.getJSONObject(i)
                val ip = n.optString("wg_ip")
                if (ip.isNotBlank()) {
                    out += Node(n.optString("name", ip), ip, n.optString("role", ""))
                }
            }
        }
        cached = out
        return out
    }
}
