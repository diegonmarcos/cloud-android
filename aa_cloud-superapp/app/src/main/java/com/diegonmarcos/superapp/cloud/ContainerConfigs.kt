package com.diegonmarcos.superapp.cloud

import com.diegonmarcos.superapp.launcher.Sections

/**
 * The app-specific half of a container's Infos tab.
 *
 * A container's generic facts — VM, DNS, port, exposure — are the same shape
 * for all 102 of them. What actually matters about a container is the thing it
 * is FOR, and that differs per app: Caddy is its routing table, WireGuard is
 * its mesh, Authelia is which routes it guards. Showing only the generic fields
 * describes the box and says nothing about the job.
 *
 * Every block here is DECLARATIVE and already on the device — the same
 * data/services_*.json and data/mesh.json the dashboard is built from. Nothing
 * is fetched, so the tab stays instant and works with the mesh down, which is
 * exactly when you want to read the config.
 *
 * This is a deliberate `when` over container names rather than a data-driven
 * table: what to show for Caddy is genuine per-app knowledge, not a parameter,
 * and pretending otherwise would mean inventing a schema that describes only
 * the cases already written. An unknown container simply gets no extra block.
 */
object ContainerConfigs {

    data class Block(val title: String, val subtitle: String, val rows: List<Pair<String, String>>)

    fun forContainer(name: String): List<Block> = when {
        name.startsWith("caddy")     -> caddyRoutes()
        name.startsWith("wireguard") -> meshBlocks()
        name.startsWith("authelia")  -> autheliaGuarded()
        else -> emptyList()
    }

    /** Caddy IS the public surface: every declared public_url and what it
     *  proxies to. Reading this beside the container is the point — it answers
     *  "is this host actually routed?" without opening a Caddyfile. */
    private fun caddyRoutes(): List<Block> {
        val pub = Sections.publicServices().filter { it.publicUrl.isNotBlank() }
        if (pub.isEmpty()) return emptyList()
        return listOf(
            Block(
                title = "Public routes (${pub.size})",
                subtitle = "public host → upstream, from data/services_public.json",
                rows = pub.map { it.publicUrl.removePrefix("https://") to it.privateDns.ifBlank { it.name } },
            )
        )
    }

    /** WireGuard: the declared mesh, not a live handshake. Nodes with their wg
     *  IPs, then the transports — wg0 direct UDP and the wstunnel fallback —
     *  because which one is carrying traffic is the usual question. */
    private fun meshBlocks(): List<Block> {
        val mesh = runCatching { Sections.mesh() }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Block>()
        if (mesh.nodes.isNotEmpty()) {
            out.add(
                Block(
                    title = "Mesh nodes (${mesh.nodes.size})",
                    subtitle = "declared in data/mesh.json — wg IP · role",
                    rows = mesh.nodes.map {
                        it.name to listOfNotNull(
                            it.wgIp.takeIf { s -> s.isNotBlank() },
                            it.role.takeIf { s -> s.isNotBlank() },
                            it.provider.takeIf { s -> s.isNotBlank() },
                        ).joinToString(" · ")
                    },
                )
            )
        }
        if (mesh.transports.isNotEmpty()) {
            out.add(
                Block(
                    title = "Transports",
                    subtitle = "wg0 direct vs the tunnelled fallback",
                    rows = mesh.transports.map {
                        it.label.ifBlank { it.name } to buildString {
                            append(it.protocol).append(':').append(it.port)
                            if (it.primary) append(" · primary")
                            if (it.fallback) append(" · fallback")
                            append(" · ").append(it.activePeers).append(" peers")
                        }
                    },
                )
            )
        }
        if (mesh.peers.isNotEmpty()) {
            out.add(
                Block(
                    title = "Peerings (${mesh.peers.size})",
                    subtitle = "from → to, allowed IPs",
                    rows = mesh.peers.map {
                        "${it.from} → ${it.to}" to it.allowedIps.joinToString(", ").ifBlank { "—" }
                    },
                )
            )
        }
        return out
    }

    /** Authelia's job is the guard list: which public routes demand auth, and
     *  which are deliberately open. The open ones are the interesting half —
     *  an unintentionally-public route is the failure this page should surface. */
    private fun autheliaGuarded(): List<Block> {
        val pub = Sections.publicServices().filter { it.publicUrl.isNotBlank() }
        if (pub.isEmpty()) return emptyList()
        val guarded = pub.filter { it.auth.isNotBlank() && it.auth != "none" }
        val open = pub.filter { it.auth.isBlank() || it.auth == "none" }
        val out = mutableListOf<Block>()
        if (guarded.isNotEmpty()) out.add(
            Block(
                title = "Guarded routes (${guarded.size})",
                subtitle = "public host → auth mode",
                rows = guarded.map { it.publicUrl.removePrefix("https://") to it.auth },
            )
        )
        if (open.isNotEmpty()) out.add(
            Block(
                title = "Unauthenticated routes (${open.size})",
                subtitle = "reachable from the internet with no Authelia gate — by design or not",
                rows = open.map { it.publicUrl.removePrefix("https://") to "none" },
            )
        )
        return out
    }
}
