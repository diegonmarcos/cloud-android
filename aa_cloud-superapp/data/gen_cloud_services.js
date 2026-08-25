#!/usr/bin/env node
// Generate data/cloud_services.json — the curated inventory for the Labs/Admin
// 3-card Cloud dashboard (Infra Apps · User Apps · Others).
//
// Source of truth: cloud/a_solutions/<svc>/build.json (git-tracked) for the live
// container set, its dns + ports.app, and the per-service `api` declaration;
// data/services_private.json for the DB-container .app:port (db_engine entries).
//
// RULE: Infra + User Apps together hold EVERY container exactly once (no dupes).
// Others = free cross-cut lists (Providers · DBs · MCP & API) that re-reference
// Infra/User entries. Re-run after a service is added/removed/re-categorised:
//     node data/gen_cloud_services.js
// (a_solutions is gitignored, so this runs locally; the committed JSON ships.)

const fs = require("fs");
const path = require("path");
const os = require("os");

const HERE = __dirname;
const AROOT = process.env.A_SOLUTIONS || path.join(os.homedir(), "git/cloud-infra/a_solutions");

function listServiceDirs(root) {
    if (!fs.existsSync(root)) return [];
    return fs.readdirSync(root, { withFileTypes: true })
        .filter((e) => e.isDirectory())
        .map((e) => e.name)
        .sort();
}

function readJson(p) {
    try {
        return JSON.parse(fs.readFileSync(p, "utf8"));
    } catch {
        return null;
    }
}

// ── pull dns + app port + api display-name from every build.json ──────────
const APP = {};        // name -> {dns, port}
const API = {};        // name -> api display_name
const MCP_NAMES = new Set();  // every service whose name contains "mcp" (data-driven)
for (const fld of listServiceDirs(AROOT)) {
    if (fld.startsWith("z_archive") || fld.startsWith("_shared")) continue;
    const b = readJson(path.join(AROOT, fld, "build.json"));
    if (!b) continue;
    const dns = typeof b.dns === "string" ? b.dns : "";
    const ports = (b.ports && typeof b.ports === "object") ? b.ports : {};
    const port = ports.app ?? Object.values(ports).find((v) => Number.isInteger(v)) ?? null;
    const suffix = fld.includes("_") ? fld.slice(fld.indexOf("_") + 1) : fld;
    const canon = b.name || suffix;
    for (const k of new Set([b.name || "", suffix, suffix.replace(/tools-/g, "")])) {
        if (k && !(k in APP)) APP[k] = [dns, port];
    }
    const api = b.api || {};
    if (api && Object.keys(api).length) {
        if (!(canon in API)) API[canon] = api.display_name || canon;
    }
    if (suffix.includes("mcp")) MCP_NAMES.add(suffix);
}

// ── real DB / storage containers, straight from a_solutions build.json's
//    `containers` map (every non-app container whose key or image is a DB) ──
const DBIMG = /(postgres|redis|mariadb|mysql|minio|mongo|surreal|valkey|memcached|clickhouse|qdrant|chroma)/i;
const DBKEY = /(db|redis|minio|postgres|maria|mysql|mongo|surreal|cache|valkey|qdrant|vector)/i;
const DBS = {};        // container_name -> [dns, port]
const DB_ORDER = [];   // preserve discovery order
for (const fld of listServiceDirs(AROOT)) {
    if (fld.startsWith("z_archive") || fld.startsWith("_shared")) continue;
    const b = readJson(path.join(AROOT, fld, "build.json"));
    if (!b) continue;
    const conts = b.containers;
    if (!conts || typeof conts !== "object") continue;
    for (const [key, c] of Object.entries(conts)) {
        if (key === "app" || !c || typeof c !== "object") continue;
        if (DBIMG.test(c.image || "") || DBKEY.test(key)) {
            const cn = c.container_name || key;
            if (!(cn in DBS)) {
                DBS[cn] = [c.dns || "", c.port ?? null];
                DB_ORDER.push(cn);
            }
        }
    }
}

// Matches Python's str.title(): every letter right after a non-letter is
// capitalized, every other letter lowercased — mid-word too (e.g. the "B" in
// "1.5bq4" -> "1.5Bq4", since a digit also breaks a "word").
function title(n) {
    let out = "";
    let prevAlpha = false;
    for (const ch of n) {
        if (/[A-Za-z]/.test(ch)) {
            out += prevAlpha ? ch.toLowerCase() : ch.toUpperCase();
            prevAlpha = true;
        } else {
            out += ch;
            prevAlpha = false;
        }
    }
    return out;
}

function titleFrom(n) {
    return title(n.replace(/-/g, " ").replace(/_/g, " "));
}

function app(n, label) {
    const [dns, port] = APP[n] || APP[n.replace(/tools-/g, "")] || ["", null];
    const host = dns.endsWith(".app") ? dns : `${n}.app`;
    const e = { name: n, label: label || titleFrom(n), url: host };
    if (port) e.port = port;
    return e;
}

function db(n, label) {
    const [h, p] = DBS[n] || [`${n}.app`, null];
    const host = h.endsWith(".app") ? h : `${n}.app`;
    const e = { name: n, label: label || titleFrom(n), url: host };
    if (p) e.port = p;
    return e;
}

// An API cross-list entry — same host/port as the container, but labelled
// with its declared api.display_name.
function api(n) {
    return app(n, API[n] || titleFrom(n));
}

function ext(label, url) {
    return { name: label, label, url, external: true };
}

const IC = {
    Security: "ic_lock", Network: "ic_wg", Observability: "ic_p_logs",
    Databases: "ic_database", Data: "ic_database", Build: "ic_p_sol_tools",
    "APIs & MCPs": "ic_code", Communications: "ic_chat", Productivity: "ic_mode_apps",
    Media: "ic_suite", Finance: "ic_p_c3_stack", "AI & Agents": "ic_ai_sparkle",
    Vault: "ic_lock", News: "ic_p_logs", Web: "ic_world", Storage: "ic_database",
    DBs: "ic_database", APIs: "ic_code", MCPs: "ic_p_c3_workflows", VMs: "ic_p_c3_vms",
    Runners: "ic_p_sol_tools", Pilots: "ic_p_c3_workflows",
};

// ── canonical partition: every container exactly once across Infra + User ──
const INFRA = [
    ["Security", ["authelia", "caddy", "caddy-l4-public", "introspect-proxy"].map((n) => app(n))],
    ["Network", ["wireguard-mesh", "wireguard-mesh-ws-tunnel", "wireguard-public", "hickory-dns"].map((n) => app(n))],
    ["Observability", ["matomo", "umami", "openobserve", "dagu", "nocodb", "dbgate", "ntfy", "sauron-forwarder", "cloud-spec"].map((n) => app(n))],
    ["Databases", [...DB_ORDER.map((n) => db(n)), app("redis"), app("postlite")]],
    ["Data", ["gitea", "backup-borg", "backup-bup", "backup-gitea"].map((n) => app(n))],
    ["APIs & MCPs", ["c3-analytics-api", "c3-infra-api", "c3-public-api", "c3-services-api", "c3-infra-mcp", "c3-services-mcp", "c3-diego-personal-data-mcp", "google-personal-mcp", "google-workspace-mcp", "mail-mcp", "mattermost-mcp", "http-to-smtp-proxy-api"].map((n) => app(n))],
    ["Build", [app("cloud-builder-x")]],
];
const USER = [
    ["Communications", ["chat-mattermost", "matrix-continuwuity", "matrix-element", "matrix-mautrix-whatsapp", "maddy", "stalwart", "cypht", "snappymail", "mail-puller", "tools-smtp-proxy"].map((n) => app(n))],
    ["Productivity", ["etherpad", "hedgedoc", "grist", "calendar-radicale", "contacts-radicale", "revealmd", "code-server", "filebrowser", "paca"].map((n) => app(n))],
    ["Media", [app("photoprism")]],
    ["Finance", [app("crawlee-cloud"), app("fin-api")]],
    ["AI & Agents", [...["ollama", "ollama-arm", "ollama-hai", "rig-agentic", "rig-agentic-hai-1.5bq4", "rig-agentic-sonn-14bq8", "db-agent", "kg-graph", "claude-openai-bridge"].map((n) => app(n)), app("cloud-cgc-mcp", "Octocode (CGC)")]],
    ["Vault", [app("vaultwarden")]],
    ["News", [app("news-gdelt")]],
    ["Web", [app("front-end")]],
    ["Storage", [ext("Oracle S3", "https://cloud.oracle.com"), ext("Google Workspace", "https://workspace.google.com")]],
];

// Others/APIs = EVERY service that declares an `api` in build.json (the full
// real list — many app containers expose their own API), labelled by api name.
const API_SVCS = Object.keys(API).sort();
const MCP_SVCS = [...MCP_NAMES].sort();   // every *mcp* service, discovered — incl. all google MCPs
// Full DB list = the real sidecar DB containers (discovery order) + the
// standalone DB services (redis / postlite are their service's `app`).
const DB_ALL = [...DB_ORDER.map((n) => db(n)), app("redis"), app("postlite")];

// ── VMs: wg-mesh nodes (data/mesh.json) + the two personal devices. Pinged
//    on wg_ip:22 (SSH) over WireGuard; relabel the `laptop` node Surface Pro.
const MESH = readJson(path.join(HERE, "mesh.json")) || {};
// Every cloud VM runs the vm-pilot, which serves an HTML dashboard on :7680
// (busybox httpd → /opt/pilot/html). dashboard=True → tap opens that dashboard
// and the status light pings :7680; else a plain wg_ip:22 (SSH) reachability tile.
const DASH_PORT = 7680;
function vm(name, ip, label, dashboard) {
    const e = { name, label: label || name, url: ip };
    if (dashboard) {
        e.port = DASH_PORT;
        e.link = `http://${ip}:${DASH_PORT}`;
    } else {
        e.port = 22;
    }
    return e;
}
const MESH_NODES = MESH.nodes || [];
const NODE_IP = Object.fromEntries(MESH_NODES.map((n) => [n.name, n.wg_ip]));
// Cloud VMs (run vm-pilot) = mesh nodes with role hub/spoke; clients are devices.
const PILOT_NODES = MESH_NODES.filter((n) => n.name && n.wg_ip && ["hub", "spoke"].includes(n.role));
const VMS = [];
for (const n of MESH_NODES) {
    const { name: nm, wg_ip: ip, role } = n;
    if (nm && ip) VMS.push(vm(nm, ip, nm === "laptop" ? "Surface Pro" : nm, ["hub", "spoke"].includes(role)));
}
VMS.push(vm("galaxy-s21", "10.0.0.9", "Galaxy S21"));

// The 5 vm-pilots (one per cloud VM) → each opens its :7680 dashboard.
const PILOTS = PILOT_NODES.map((n) => vm(`pilot-${n.name}`, n.wg_ip, `Pilot · ${n.name}`, true));
INFRA.push(["Pilots", PILOTS]);   // 5 vm-pilots under Infra Apps

// CI build runners: ARM = cloud-builder-x on oci-apps (ping its wg_ip:22),
// x86 = GitHub-hosted GHA runners (external link to the workflow runs).
const RUNNERS = [
    vm("runner-arm", NODE_IP["oci-apps"] || "10.0.0.6", "ARM (Oci-Apps)"),
    ext("x86 (GHA)", "https://github.com/diegonmarcos/cloud-unix/actions"),
];

function grp(gid, gl, subs, extra) {
    return {
        id: gid, label: gl,
        subgroups: subs.map(([sl, cs]) => ({ label: sl, icon: IC[sl] || "ic_settings", containers: cs })),
        ...(extra || {}),
    };
}

const groups = [
    grp("infra", "Infra Apps", INFRA),
    grp("user", "User Apps", USER),
    grp("providers", "Providers (VPS)", [["VMs", VMS], ["Runners", RUNNERS]], {
        icon: "ic_world",
        providers: [
            { label: "Oracle", url: "https://cloud.oracle.com" },
            { label: "GCloud", url: "https://console.cloud.google.com" },
            { label: "Cloudflare", url: "https://dash.cloudflare.com" },
            { label: "GitHub", url: "https://github.com/diegonmarcos" },
            { label: "Hetzner", url: "https://console.hetzner.cloud" },
            { label: "Nvidia", url: "https://build.nvidia.com" },
        ],
    }),
    grp("dbs", "DBs (storage)", [["DBs", [...DB_ALL, ext("Oracle S3 (backups)", "https://cloud.oracle.com")]]]),
    grp("mcpapi", "MCP & API", [["APIs", API_SVCS.map((n) => api(n))], ["MCPs", MCP_SVCS.map((n) => app(n))]]),
];

const out = {
    _doc: "GENERATED by data/gen_cloud_services.js from cloud/a_solutions. 3-card Labs/Admin dashboard. Infra+User hold EVERY container exactly once (DB containers under Infra/Databases, MCP/API containers under Infra/APIs & MCPs, storage backends under User/Storage). Others = free cross-cut lists: Providers · DBs · MCP & API, where APIs = every service that declares an `api` in its build.json (the full real list). external:true entries open their URL directly with no status light. Re-run the generator after a service changes.",
    groups,
};
fs.writeFileSync(path.join(HERE, "cloud_services.json"), JSON.stringify(out, null, 2));

const placed = [...INFRA, ...USER].flatMap(([, cs]) => cs.map((c) => c.name));
const counts = {};
for (const k of placed) counts[k] = (counts[k] || 0) + 1;
const dup = Object.keys(counts).filter((k) => counts[k] > 1);
console.log(`containers placed (Infra+User): ${placed.length} · dupes: ${dup.length ? dup : "none"}`);
console.log(`Others/APIs (services with an api): ${API_SVCS.length}`);
if (dup.length) {
    console.error("DUPLICATES — fix the taxonomy");
    process.exit(1);
}
