#!/usr/bin/env python3
"""Generate data/cloud_services.json — the curated inventory for the Labs/Admin
3-card Cloud dashboard (Infra Apps · User Apps · Others).

Source of truth: cloud/a_solutions/<svc>/build.json (git-tracked) for the live
container set, its dns + ports.app, and the per-service `api` declaration;
data/services_private.json for the DB-container .app:port (db_engine entries).

RULE: Infra + User Apps together hold EVERY container exactly once (no dupes).
Others = free cross-cut lists (Providers · DBs · MCP & API) that re-reference
Infra/User entries. Re-run after a service is added/removed/re-categorised:
    python3 data/gen_cloud_services.py
(a_solutions is gitignored, so this runs locally; the committed JSON ships.)
"""
import json, glob, os, sys, collections

HERE = os.path.dirname(os.path.abspath(__file__))
AROOT = os.environ.get("A_SOLUTIONS",
    os.path.expanduser("~/git/cloud-infra/a_solutions"))

# ── pull dns + app port + api display-name from every build.json ──────────
APP = {}        # name -> (dns, port)
API = {}        # name -> api display_name
MCP_NAMES = set()  # every service whose name contains "mcp" (data-driven)
for d in sorted(glob.glob(f"{AROOT}/*/")):
    fld = d.rstrip("/").split("/")[-1]
    if fld.startswith(("z_archive", "_shared")):
        continue
    bj = os.path.join(d, "build.json")
    if not os.path.exists(bj):
        continue
    try:
        b = json.load(open(bj))
    except Exception:
        continue
    dns = b.get("dns"); dns = dns if isinstance(dns, str) else ""
    ports = b.get("ports") if isinstance(b.get("ports"), dict) else {}
    port = ports.get("app") or next((v for v in ports.values() if isinstance(v, int)), None)
    suffix = fld.split("_", 1)[1] if "_" in fld else fld
    canon = b.get("name") or suffix
    for k in {b.get("name", ""), suffix, suffix.replace("tools-", "")}:
        if k:
            APP.setdefault(k, (dns, port))
    api = b.get("api") or {}
    if api:                                    # one entry per service (canonical name)
        API.setdefault(canon, api.get("display_name") or canon)
    if "mcp" in suffix:                        # every MCP service (data-driven,
        MCP_NAMES.add(suffix)                  # by folder id — name may drop "mcp")

# ── real DB / storage containers, straight from a_solutions build.json's
#    `containers` map (every non-app container whose key or image is a DB) ──
import re
_DBIMG = re.compile(r"(postgres|redis|mariadb|mysql|minio|mongo|surreal|valkey|memcached|clickhouse|qdrant|chroma)", re.I)
_DBKEY = re.compile(r"(db|redis|minio|postgres|maria|mysql|mongo|surreal|cache|valkey|qdrant|vector)", re.I)
DBS = {}        # container_name -> (dns, port)
DB_ORDER = []   # preserve discovery order
for d in sorted(glob.glob(f"{AROOT}/*/")):
    fld = d.rstrip("/").split("/")[-1]
    if fld.startswith(("z_archive", "_shared")):
        continue
    bj = os.path.join(d, "build.json")
    if not os.path.exists(bj):
        continue
    try:
        b = json.load(open(bj))
    except Exception:
        continue
    conts = b.get("containers")
    if not isinstance(conts, dict):
        continue
    for key, c in conts.items():
        if key == "app" or not isinstance(c, dict):
            continue
        if _DBIMG.search(c.get("image", "") or "") or _DBKEY.search(key):
            cn = c.get("container_name", key)
            if cn not in DBS:
                DBS[cn] = (c.get("dns", "") or "", c.get("port"))
                DB_ORDER.append(cn)


def _title(n):
    return n.replace("-", " ").replace("_", " ").title()


def app(n, label=None):
    dns, port = APP.get(n, APP.get(n.replace("tools-", ""), ("", None)))
    host = dns if dns.endswith(".app") else f"{n}.app"
    e = {"name": n, "label": label or _title(n), "url": host}
    if port:
        e["port"] = port
    return e


def db(n, label=None):
    h, p = DBS.get(n, (f"{n}.app", None))
    host = h if h.endswith(".app") else f"{n}.app"
    e = {"name": n, "label": label or _title(n), "url": host}
    if p:
        e["port"] = p
    return e


def api(n):
    """An API cross-list entry — same host/port as the container, but labelled
    with its declared api.display_name."""
    e = app(n, label=API.get(n, _title(n)))
    return e


def ext(label, url):
    return {"name": label, "label": label, "url": url, "external": True}


IC = {"Security": "ic_lock", "Network": "ic_wg", "Observability": "ic_p_logs",
      "Databases": "ic_database", "Data": "ic_database", "Build": "ic_p_sol_tools",
      "APIs & MCPs": "ic_code", "Communications": "ic_chat", "Productivity": "ic_mode_apps",
      "Media": "ic_suite", "Finance": "ic_p_c3_stack", "AI & Agents": "ic_ai_sparkle",
      "Vault": "ic_lock", "News": "ic_p_logs", "Web": "ic_world", "Storage": "ic_database",
      "DBs": "ic_database", "APIs": "ic_code", "MCPs": "ic_p_c3_workflows", "VMs": "ic_p_c3_vms",
      "Runners": "ic_p_sol_tools", "Pilots": "ic_p_c3_workflows"}

# ── canonical partition: every container exactly once across Infra + User ──
INFRA = [
    ("Security", [app(n) for n in ["authelia", "caddy", "caddy-l4-public", "introspect-proxy"]]),
    ("Network", [app(n) for n in ["wireguard-mesh", "wireguard-mesh-ws-tunnel", "wireguard-public", "hickory-dns"]]),
    ("Observability", [app(n) for n in ["matomo", "umami", "openobserve", "dagu", "nocodb", "dbgate", "ntfy", "sauron-forwarder", "cloud-spec"]]),
    ("Databases", [db(n) for n in DB_ORDER] + [app("redis"), app("postlite")]),
    ("Data", [app(n) for n in ["gitea", "backup-borg", "backup-bup", "backup-gitea"]]),
    ("APIs & MCPs", [app(n) for n in ["c3-analytics-api", "c3-infra-api", "c3-public-api", "c3-services-api", "c3-infra-mcp", "c3-services-mcp", "c3-diego-personal-data-mcp", "google-personal-mcp", "google-workspace-mcp", "mail-mcp", "mattermost-mcp", "http-to-smtp-proxy-api"]]),
    ("Build", [app("cloud-builder-x")]),
]
USER = [
    ("Communications", [app(n) for n in ["chat-mattermost", "matrix-continuwuity", "matrix-element", "matrix-mautrix-whatsapp", "maddy", "stalwart", "cypht", "snappymail", "mail-puller", "tools-smtp-proxy"]]),
    ("Productivity", [app(n) for n in ["etherpad", "hedgedoc", "grist", "calendar-radicale", "contacts-radicale", "revealmd", "code-server", "filebrowser", "paca"]]),
    ("Media", [app("photoprism")]),
    ("Finance", [app("crawlee-cloud"), app("fin-api")]),
    ("AI & Agents", [app(n) for n in ["ollama", "ollama-arm", "ollama-hai", "rig-agentic", "rig-agentic-hai-1.5bq4", "rig-agentic-sonn-14bq8", "db-agent", "kg-graph", "claude-openai-bridge"]] + [app("cloud-cgc-mcp", "Octocode (CGC)")]),
    ("Vault", [app("vaultwarden")]),
    ("News", [app("news-gdelt")]),
    ("Web", [app("front-end")]),
    ("Storage", [ext("Oracle S3", "https://cloud.oracle.com"), ext("Google Workspace", "https://workspace.google.com")]),
]

# Others/APIs = EVERY service that declares an `api` in build.json (the full
# real list — many app containers expose their own API), labelled by api name.
API_SVCS = sorted(API.keys())
MCP_SVCS = sorted(MCP_NAMES)   # every *mcp* service, discovered — incl. all google MCPs
# Full DB list = the real sidecar DB containers (discovery order) + the
# standalone DB services (redis / postlite are their service's `app`).
DB_ALL = [db(n) for n in DB_ORDER] + [app("redis"), app("postlite")]

# ── VMs: wg-mesh nodes (data/mesh.json) + the two personal devices. Pinged
#    on wg_ip:22 (SSH) over WireGuard; relabel the `laptop` node Surface Pro.
MESH = json.load(open(os.path.join(HERE, "mesh.json")))
# Every cloud VM runs the vm-pilot, which serves an HTML dashboard on :7680
# (busybox httpd → /opt/pilot/html). dashboard=True → tap opens that dashboard
# and the status light pings :7680; else a plain wg_ip:22 (SSH) reachability tile.
DASH_PORT = 7680
def vm(name, ip, label=None, dashboard=False):
    e = {"name": name, "label": label or name, "url": ip}
    if dashboard:
        e["port"] = DASH_PORT
        e["link"] = f"http://{ip}:{DASH_PORT}"
    else:
        e["port"] = 22
    return e
NODE_IP = {n.get("name"): n.get("wg_ip") for n in (MESH.get("nodes") or [])}
# Cloud VMs (run vm-pilot) = mesh nodes with role hub/spoke; clients are devices.
PILOT_NODES = [n for n in (MESH.get("nodes") or [])
               if n.get("name") and n.get("wg_ip") and n.get("role") in ("hub", "spoke")]
VMS = []
for n in (MESH.get("nodes") or []):
    nm, ip, role = n.get("name"), n.get("wg_ip"), n.get("role")
    if nm and ip:
        VMS.append(vm(nm, ip, "Surface Pro" if nm == "laptop" else nm,
                      dashboard=(role in ("hub", "spoke"))))
VMS.append(vm("galaxy-s21", "10.0.0.9", "Galaxy S21"))

# The 5 vm-pilots (one per cloud VM) → each opens its :7680 dashboard.
PILOTS = [vm(f"pilot-{n['name']}", n["wg_ip"], f"Pilot · {n['name']}", dashboard=True)
          for n in PILOT_NODES]
INFRA.append(("Pilots", PILOTS))   # 5 vm-pilots under Infra Apps

# CI build runners: ARM = cloud-builder-x on oci-apps (ping its wg_ip:22),
# x86 = GitHub-hosted GHA runners (external link to the workflow runs).
RUNNERS = [
    vm("runner-arm", NODE_IP.get("oci-apps", "10.0.0.6"), "ARM (Oci-Apps)"),
    ext("x86 (GHA)", "https://github.com/diegonmarcos/cloud-unix/actions"),
]


def grp(gid, gl, subs, **extra):
    return {"id": gid, "label": gl,
            "subgroups": [{"label": sl, "icon": IC.get(sl, "ic_settings"), "containers": cs}
                          for sl, cs in subs], **extra}


groups = [grp("infra", "Infra Apps", INFRA), grp("user", "User Apps", USER),
          grp("providers", "Providers (VPS)", [("VMs", VMS), ("Runners", RUNNERS)], icon="ic_world", providers=[
              {"label": "Oracle", "url": "https://cloud.oracle.com"},
              {"label": "GCloud", "url": "https://console.cloud.google.com"},
              {"label": "Cloudflare", "url": "https://dash.cloudflare.com"},
              {"label": "GitHub", "url": "https://github.com/diegonmarcos"},
              {"label": "Hetzner", "url": "https://console.hetzner.cloud"},
              {"label": "Nvidia", "url": "https://build.nvidia.com"}]),
          grp("dbs", "DBs (storage)", [("DBs", DB_ALL + [ext("Oracle S3 (backups)", "https://cloud.oracle.com")])]),
          grp("mcpapi", "MCP & API", [("APIs", [api(n) for n in API_SVCS]),
                                       ("MCPs", [app(n) for n in MCP_SVCS])])]

out = {"_doc": "GENERATED by data/gen_cloud_services.py from cloud/a_solutions. 3-card Labs/Admin dashboard. Infra+User hold EVERY container exactly once (DB containers under Infra/Databases, MCP/API containers under Infra/APIs & MCPs, storage backends under User/Storage). Others = free cross-cut lists: Providers · DBs · MCP & API, where APIs = every service that declares an `api` in its build.json (the full real list). external:true entries open their URL directly with no status light. Re-run the generator after a service changes.", "groups": groups}
json.dump(out, open(os.path.join(HERE, "cloud_services.json"), "w"), indent=2, ensure_ascii=False)

placed = [c["name"] for _, subs in (INFRA + USER) for c in subs]
dup = [k for k, v in collections.Counter(placed).items() if v > 1]
print(f"containers placed (Infra+User): {len(placed)} · dupes: {dup or 'none'}")
print(f"Others/APIs (services with an api): {len(API_SVCS)}")
if dup:
    sys.exit("DUPLICATES — fix the taxonomy")
