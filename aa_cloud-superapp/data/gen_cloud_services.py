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
    os.path.expanduser("~/git/cloud/a_solutions"))

# ── pull dns + app port + api display-name from every build.json ──────────
APP = {}      # name -> (dns, port)
API = {}      # name -> api display_name
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
    for k in {b.get("name", ""), suffix, suffix.replace("tools-", "")}:
        if k:
            APP.setdefault(k, (dns, port))
    api = b.get("api") or {}
    if api:                                    # one entry per service (canonical name)
        canon = b.get("name") or suffix
        API.setdefault(canon, api.get("display_name") or canon)

# ── DB containers (real .app:port) from the private-services snapshot ──────
DBS = {}
for e in json.load(open(os.path.join(HERE, "services_private.json"))):
    if e.get("db_engine"):
        h, _, p = (e.get("private_dns") or "").partition(":")
        DBS[e["name"]] = (h, int(p) if p.isdigit() else None)


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
      "DBs": "ic_database", "APIs": "ic_code", "MCPs": "ic_p_c3_workflows"}

# ── canonical partition: every container exactly once across Infra + User ──
INFRA = [
    ("Security", [app(n) for n in ["authelia", "caddy", "caddy-l4-public", "introspect-proxy"]]),
    ("Network", [app(n) for n in ["wireguard-mesh", "wireguard-mesh-ws-tunnel", "wireguard-public", "hickory-dns"]]),
    ("Observability", [app(n) for n in ["matomo", "umami", "openobserve", "dagu", "nocodb", "dbgate", "ntfy", "sauron-forwarder", "cloud-spec"]]),
    ("Databases", [db(n) for n in ["redis", "authelia-redis", "umami-db", "etherpad_postgres", "hedgedoc_postgres", "mattermost-postgres", "photoprism_mariadb", "crawlee_db", "crawlee_redis", "crawlee_minio"]] + [app("postlite")]),
    ("Data", [app(n) for n in ["gitea", "backup-borg", "backup-bup", "backup-gitea"]]),
    ("APIs & MCPs", [app(n) for n in ["c3-analytics-api", "c3-infra-api", "c3-public-api", "c3-services-api", "c3-infra-mcp", "c3-infra-mcp-api", "c3-services-mcp", "c3-services-mcp-api", "c3-specs-docs-mcp", "cloud-cgc-mcp", "c3-diego-personal-data-mcp", "google-personal-mcp", "google-workspace-mcp", "mail-mcp", "mattermost-mcp", "http-to-smtp-proxy-api"]]),
    ("Build", [app("cloud-builder-x")]),
]
USER = [
    ("Communications", [app(n) for n in ["chat-mattermost", "matrix-continuwuity", "matrix-element", "matrix-mautrix-whatsapp", "maddy", "stalwart", "cypht", "snappymail", "mail-puller", "tools-smtp-proxy"]]),
    ("Productivity", [app(n) for n in ["etherpad", "hedgedoc", "grist", "calendar-radicale", "contacts-radicale", "revealmd", "code-server", "filebrowser", "paca"]]),
    ("Media", [app("photoprism")]),
    ("Finance", [app("crawlee-cloud"), app("fin-api")]),
    ("AI & Agents", [app(n) for n in ["ollama", "ollama-arm", "ollama-hai", "rig-agentic", "rig-agentic-hai-1.5bq4", "rig-agentic-sonn-14bq8", "db-agent", "kg-graph"]]),
    ("Vault", [app("vaultwarden")]),
    ("News", [app("news-gdelt")]),
    ("Web", [app("front-end")]),
    ("Storage", [ext("Oracle S3", "https://cloud.oracle.com"), ext("Google Workspace", "https://workspace.google.com")]),
]

# Others/APIs = EVERY service that declares an `api` in build.json (the full
# real list — many app containers expose their own API), labelled by api name.
API_SVCS = sorted(API.keys())
MCP_SVCS = ["c3-infra-mcp", "c3-infra-mcp-api", "c3-services-mcp", "c3-services-mcp-api",
            "c3-specs-docs-mcp", "cloud-cgc-mcp", "c3-diego-personal-data-mcp",
            "google-personal-mcp", "google-workspace-mcp", "mail-mcp", "mattermost-mcp"]
DB_LIST = ["redis", "authelia-redis", "umami-db", "etherpad_postgres", "hedgedoc_postgres",
           "mattermost-postgres", "photoprism_mariadb", "crawlee_db", "crawlee_redis", "crawlee_minio"]


def grp(gid, gl, subs):
    return {"id": gid, "label": gl,
            "subgroups": [{"label": sl, "icon": IC.get(sl, "ic_settings"), "containers": cs}
                          for sl, cs in subs]}


groups = [grp("infra", "Infra Apps", INFRA), grp("user", "User Apps", USER),
          {"id": "providers", "label": "Providers (VPS)", "icon": "ic_world", "providers": [
              {"label": "Oracle", "url": "https://cloud.oracle.com"},
              {"label": "GCloud", "url": "https://console.cloud.google.com"},
              {"label": "Cloudflare", "url": "https://dash.cloudflare.com"},
              {"label": "GitHub", "url": "https://github.com/diegonmarcos"},
              {"label": "Hetzner", "url": "https://console.hetzner.cloud"},
              {"label": "Nvidia", "url": "https://build.nvidia.com"}]},
          grp("dbs", "DBs (storage)", [("DBs", [db(n) for n in DB_LIST] + [app("postlite"), ext("Oracle S3 (backups)", "https://cloud.oracle.com")])]),
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
