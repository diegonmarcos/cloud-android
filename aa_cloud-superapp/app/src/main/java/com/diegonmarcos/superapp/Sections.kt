package com.diegonmarcos.superapp

import android.content.Context
import android.util.Base64
import org.json.JSONArray

/**
 * Runtime view of `build.json::ui.sections` — the single source of truth
 * for the navigation taxonomy. Baked into BuildConfig.UI_SECTIONS_JSON_B64
 * at gradle eval time, parsed lazily at first access.
 *
 * Adding / reordering sections, pages, or flipping `bottom_nav` is a
 * build.json edit — never a Kotlin edit.
 */
object Sections {

    data class Section(
        val id: String,
        val label: String,
        val iconName: String,
        val module: String?,
        val bottomNav: Boolean,
        val isMasterIndex: Boolean,
        val pages: List<Page>,
        val defaultChildren: List<String>,
    )

    data class Page(
        val id: String,
        val label: String,
        val iconName: String?,
        /** Second-level children. Two sources in build.json::pages[X]:
         *  `sub_pages` (e.g. mail Settings → 11 FragmentOptions* tabs) or
         *  `menu` (e.g. mail More → 8 overflow items). Both flatten into
         *  this single list — the drawer treats them identically. */
        val subPages: List<Page> = emptyList(),
    )

    /** App-level action tile shown in the Home master TileGrid below the
     *  section tiles. `actionType` is the dispatcher key in MainActivity. */
    data class Action(
        val id: String,
        val label: String,
        val iconName: String,
        val actionType: String,
    )

    data class Sample(val title: String, val subtitle: String)

    /** A grouped Home tile — `id` is "section:<X>" or "page:<sec>/<page>",
     *  same format MainActivity.onTileClicked already understands. */
    data class HomeTile(val id: String, val label: String, val iconName: String)
    data class HomeGroup(val title: String, val tiles: List<HomeTile>)

    /** One row of a WG mesh status table. */
    data class MeshPeer(
        val name: String,
        val wgIp: String,
        val endpoint: String,
        val region: String,
        val role: String,
        val allowedIps: String,
        val keepalive: Int,
        val vmId: String,
    )
    data class Mesh(
        val id: String,
        val label: String,
        val subnet: String,
        val port: Int,
        val mtu: Int,
        val topology: String,
        val peers: List<MeshPeer>,
    )

    data class ServiceInfo(
        val name: String,
        val publicUrl: String,
        val auth: String,
        val vm: String,
        val category: String,
        val enabled: Boolean,
    )

    @Volatile private var cached:        List<Section>?           = null
    @Volatile private var cachedActions: List<Action>?            = null
    @Volatile private var cachedSamples: Map<String, List<Sample>>? = null
    @Volatile private var cachedGroups:  List<HomeGroup>?         = null
    @Volatile private var cachedMeshes:  List<Mesh>?              = null
    @Volatile private var cachedSvc:     List<ServiceInfo>?       = null

    fun all(): List<Section> {
        cached?.let { return it }
        val json = String(Base64.decode(BuildConfig.UI_SECTIONS_JSON_B64, Base64.NO_WRAP))
        val arr = JSONArray(json)
        val parsed = mutableListOf<Section>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)

            val pages = mutableListOf<Page>()
            o.optJSONArray("pages")?.let { pa ->
                for (j in 0 until pa.length()) {
                    val po = pa.getJSONObject(j)
                    val pIcon = po.optString("icon", "").takeIf { it.isNotEmpty() }
                    val subs = mutableListOf<Page>()
                    // Two equivalent shapes — sub_pages OR menu.
                    val subArr = po.optJSONArray("sub_pages") ?: po.optJSONArray("menu")
                    if (subArr != null) {
                        for (k in 0 until subArr.length()) {
                            val so = subArr.getJSONObject(k)
                            val sIcon = so.optString("icon", "").takeIf { it.isNotEmpty() }
                            subs.add(Page(so.getString("id"), so.getString("label"), sIcon))
                        }
                    }
                    pages.add(Page(po.getString("id"), po.getString("label"), pIcon, subs))
                }
            }

            val kids = mutableListOf<String>()
            o.optJSONArray("drawer_default_children")?.let { ka ->
                for (j in 0 until ka.length()) kids.add(ka.getString(j))
            }

            // `module` may be JSON null (e.g. the master Home index). org.json
            // surfaces it as the string "null" via optString → normalize.
            val rawModule = o.optString("module", "")
            val module = rawModule.takeIf { it.isNotEmpty() && it != "null" }

            parsed.add(
                Section(
                    id              = o.getString("id"),
                    label           = o.getString("label"),
                    iconName        = o.optString("icon", "ic_settings"),
                    module          = module,
                    bottomNav       = o.optBoolean("bottom_nav", false),
                    isMasterIndex   = o.optBoolean("is_master_index", false),
                    pages           = pages,
                    defaultChildren = kids,
                )
            )
        }
        cached = parsed
        return parsed
    }

    fun byId(id: String): Section? = all().firstOrNull { it.id == id }

    fun defaultSectionId(): String = BuildConfig.UI_DEFAULT_SECTION

    fun homeActions(): List<Action> {
        cachedActions?.let { return it }
        val json = String(Base64.decode(BuildConfig.UI_HOME_ACTIONS_B64, Base64.NO_WRAP))
        val arr = JSONArray(json)
        val parsed = mutableListOf<Action>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            parsed.add(
                Action(
                    id         = o.getString("id"),
                    label      = o.getString("label"),
                    iconName   = o.optString("icon", "ic_settings"),
                    actionType = o.getString("action_type"),
                )
            )
        }
        cachedActions = parsed
        return parsed
    }

    /** Resolve `icon` name from build.json to a drawable res id; 0 if missing. */
    fun iconResFor(ctx: Context, name: String): Int =
        ctx.resources.getIdentifier(name, "drawable", ctx.packageName)

    /** Per-page sample content from build.json::ui.page_samples. Keyed by
     *  "<section>/<page>". Returns empty list if no samples for that key. */
    fun pageSamples(key: String): List<Sample> {
        loadSamples()
        return cachedSamples?.get(key).orEmpty()
    }

    /** build.json::ui.home_groups — themed Home master view. Empty list
     *  means the legacy flat all-sections grid is used. */
    fun homeGroups(): List<HomeGroup> {
        cachedGroups?.let { return it }
        val json = String(Base64.decode(BuildConfig.UI_HOME_GROUPS_B64, Base64.NO_WRAP))
        val arr  = JSONArray(json)
        val parsed = mutableListOf<HomeGroup>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val tilesArr = o.optJSONArray("tiles") ?: continue
            val tiles = mutableListOf<HomeTile>()
            for (j in 0 until tilesArr.length()) {
                val t = tilesArr.getJSONObject(j)
                tiles.add(
                    HomeTile(
                        id       = t.getString("id"),
                        label    = t.getString("label"),
                        iconName = t.optString("icon", "ic_settings"),
                    )
                )
            }
            parsed.add(HomeGroup(o.getString("title"), tiles))
        }
        cachedGroups = parsed
        return parsed
    }

    /** build.json::ui.meshes — list of WG meshes (wg-mesh + wg-public). */
    fun meshes(): List<Mesh> {
        cachedMeshes?.let { return it }
        val json = String(Base64.decode(BuildConfig.UI_MESHES_B64, Base64.NO_WRAP))
        val arr = JSONArray(json)
        val out = mutableListOf<Mesh>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val peersArr = o.optJSONArray("peers") ?: org.json.JSONArray()
            val peers = mutableListOf<MeshPeer>()
            for (j in 0 until peersArr.length()) {
                val p = peersArr.getJSONObject(j)
                peers.add(
                    MeshPeer(
                        name       = p.getString("name"),
                        wgIp       = p.optString("wg_ip", ""),
                        endpoint   = p.optString("endpoint", ""),
                        region     = p.optString("region", ""),
                        role       = p.optString("role", ""),
                        allowedIps = p.optString("allowed_ips", ""),
                        keepalive  = p.optInt("keepalive", 0),
                        vmId       = p.optString("vm_id", ""),
                    )
                )
            }
            out.add(
                Mesh(
                    id       = o.getString("id"),
                    label    = o.getString("label"),
                    subnet   = o.optString("subnet", ""),
                    port     = o.optInt("port", 0),
                    mtu      = o.optInt("mtu", 1420),
                    topology = o.optString("topology", ""),
                    peers    = peers,
                )
            )
        }
        cachedMeshes = out
        return out
    }

    /** build.json::ui.services_inventory — static C3/Health source until
     *  the Rust cloud_url_health binary publishes its JSON. */
    fun servicesInventory(): List<ServiceInfo> {
        cachedSvc?.let { return it }
        val json = String(Base64.decode(BuildConfig.UI_SERVICES_B64, Base64.NO_WRAP))
        val arr = JSONArray(json)
        val out = mutableListOf<ServiceInfo>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                ServiceInfo(
                    name      = o.getString("name"),
                    publicUrl = o.optString("public_url", ""),
                    auth      = o.optString("auth", ""),
                    vm        = o.optString("vm", ""),
                    category  = o.optString("category", ""),
                    enabled   = o.optBoolean("enabled", true),
                )
            )
        }
        cachedSvc = out
        return out
    }

    private fun loadSamples() {
        if (cachedSamples != null) return
        val json = String(Base64.decode(BuildConfig.UI_PAGE_SAMPLES_B64, Base64.NO_WRAP))
        val obj  = org.json.JSONObject(json)
        val parsed = mutableMapOf<String, List<Sample>>()
        val it = obj.keys()
        while (it.hasNext()) {
            val k = it.next()
            val arr = obj.optJSONArray(k) ?: continue
            val items = mutableListOf<Sample>()
            for (i in 0 until arr.length()) {
                val po = arr.getJSONObject(i)
                items.add(Sample(po.getString("title"), po.optString("subtitle", "")))
            }
            parsed[k] = items
        }
        cachedSamples = parsed
    }
}
