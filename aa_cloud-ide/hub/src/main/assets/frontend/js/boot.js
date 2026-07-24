// boot.js — wire the shell: profiles top-nav, command sections, search, find.
(async function () {
  let profiles = [];
  let current = null;

  // Load runtime UI config (theme/font/terminal/keybindings) BEFORE any pane.
  // ponytail: Phase 2b — on Tauri, invoke the Rust commands directly; on the
  // WebView/browser path (no __TAURI__) load the static JSON the build emits
  // instead (frozen paths/format: data/config.json, data/profiles.json).
  try {
    MYK.config = window.__TAURI__
      ? await window.__TAURI__.core.invoke("get_config")
      : (window.MYK_CONFIG || await fetch("data/config.json").then((r) => (r.ok ? r.json() : {})).catch(() => ({})));
  } catch (e) { console.error("get_config failed", e); MYK.config = {}; }

  try {
    const res = window.__TAURI__
      ? await window.__TAURI__.core.invoke("get_profiles")
      : (window.MYK_PROFILES || await fetch("data/profiles.json").then((r) => (r.ok ? r.json() : { profiles: [] })).catch(() => ({ profiles: [] })));
    profiles = res.profiles || [];
  } catch (e) { console.error("get_profiles failed", e); }
  if (profiles.length === 0) profiles = [{ name: "default", display_name: "Shell", sections: [] }];
  Palette.profiles = profiles;
  Palette.runItem = runItem;

  // Top-nav pills
  const nav = document.getElementById("profiles");
  const ROW1 = new Set(["file-browser", "web-browser", "agentic"]);
  profiles.forEach((p, i) => {
    if (ROW1.has(p.name)) return;   // row-1 home profiles (File Browser / Browser / Agentic)
    const pill = document.createElement("div");
    pill.className = "profile-pill" + (i === 0 ? " active" : "");
    pill.textContent = p.display_name || p.name;
    pill.addEventListener("click", () => selectProfile(p, pill));
    nav.appendChild(pill);
  });

  function selectProfile(p, pill) {
    current = p;
    for (const el of document.querySelectorAll(".profile-pill")) el.classList.remove("active");
    pill.classList.add("active");
    buildSections(p);
    Tabs.switchProfile(p);
  }

  // Per-profile command sections
  function buildSections(p) {
    const host = document.getElementById("sections");
    host.innerHTML = "";
    for (const sec of p.sections || []) {
      const t = document.createElement("div");
      t.className = "section-title"; t.textContent = sec.title;
      host.appendChild(t);
      for (const item of sec.items || []) {
        const b = document.createElement("button");
        b.className = "cmd-item";
        b.textContent = item.label;
        b.dataset.search = (item.label + " " + (item.cmd || "")).toLowerCase();
        b.addEventListener("click", () => runItem(item));
        host.appendChild(b);
      }
    }
    filterSearch(document.getElementById("search").value);
  }

  // Run a command item in the active pane. Convention: a cmd ending in a
  // space is PREFILLED (no Enter) so the user can add args; otherwise it runs.
  function runItem(item) {
    const id = MYK.activePane;
    if (!id || !item.cmd) return;
    const run = !/\s$/.test(item.cmd);
    Transport.ptyWrite(id, run ? item.cmd + "\n" : item.cmd);
    MYK.panes.get(id)?.term.focus();
  }

  // Live search filter over command items
  function filterSearch(q) {
    q = (q || "").toLowerCase();
    for (const b of document.querySelectorAll(".cmd-item"))
      b.classList.toggle("hidden", q && !b.dataset.search.includes(q));
    for (const t of document.querySelectorAll(".section-title")) t.style.display = "";
  }
  document.getElementById("search").addEventListener("input", (e) => filterSearch(e.target.value));

  // Buttons + find bar
  document.getElementById("btn-newtab").addEventListener("click", () => Tabs.newTab());
  document.getElementById("btn-sidebar").addEventListener("click", () =>
    document.getElementById("sidebar").classList.toggle("hidden"));
  document.getElementById("find-next").addEventListener("click", () => Find.next());
  document.getElementById("find-prev").addEventListener("click", () => Find.prev());
  document.getElementById("find-close").addEventListener("click", () => Find.close());
  document.getElementById("find-input").addEventListener("keydown", (e) => {
    if (e.key === "Enter") Find.next();
    if (e.key === "Escape") Find.close();
  });
  window.addEventListener("resize", () => { if (Tabs.active) MYK._fitTab(Tabs.active); });
  window.addEventListener("beforeunload", () => Tabs.saveSession());

  // Global menu (top-right ⋮): restore session, about
  const menuBtn = document.getElementById("btn-menu");
  const menuDrop = document.getElementById("menu-dropdown");
  menuBtn.addEventListener("click", (e) => {
    e.stopPropagation();
    if (window.Configs) window.Configs.open();
    else menuDrop.hidden = !menuDrop.hidden;
  });
  document.addEventListener("click", () => { menuDrop.hidden = true; });

  document.getElementById("menu-restore-session").addEventListener("click", () => Tabs.restoreSession());

  async function showAbout() {
    let appVersion = "unknown", tauriVersion = "unknown";
    if (window.__TAURI__) {
      try { appVersion = await window.__TAURI__.app.getVersion(); } catch {}
      try { tauriVersion = await window.__TAURI__.app.getTauriVersion(); } catch {}
    }
    document.getElementById("about-body").textContent =
      `Version: ${appVersion}\nTauri: ${tauriVersion}\nProfiles loaded: ${profiles.length}\nTabs open: ${Tabs.tabs.size}`;
    document.getElementById("about").hidden = false;
  }
  document.getElementById("menu-about").addEventListener("click", showAbout);
  document.getElementById("about-close").addEventListener("click", () => { document.getElementById("about").hidden = true; });

  // Row 1 (Home): File Browser / Browser / Agentic are PROFILES (own left-side
  // commands + own independent tab group via switchProfile). File Editor is a
  // plain action (there is no file-editor profile).
  const goProfile = (name, el) => { const p = profiles.find((x) => x.name === name); if (p) selectProfile(p, el); };
  document.getElementById("btn-home-filebrowser").addEventListener("click", (e) => goProfile("file-browser", e.currentTarget));
  document.getElementById("btn-home-fileeditor").addEventListener("click", () => Tabs.openFileEditorTab((window.Configs && Configs.get(Configs.KEYS.fePath)) || null));
  document.getElementById("btn-home-browser").addEventListener("click", (e) => goProfile("web-browser", e.currentTarget));
  document.getElementById("btn-home-agentic").addEventListener("click", (e) => goProfile("agentic", e.currentTarget));
  // About lives in the Configs (⋮ → menu-about) dropdown now — no standalone button.

  // Sidebar view switcher: Commands (search + per-profile items) | Tabs (vertical, grouped)
  for (const btn of document.querySelectorAll(".sidebar-toggle-btn")) {
    btn.addEventListener("click", () => {
      for (const b of document.querySelectorAll(".sidebar-toggle-btn")) b.classList.toggle("active", b === btn);
      const isTabs = btn.dataset.view === "tabs";
      document.getElementById("commands-panel").hidden = isTabs;
      document.getElementById("tabs-panel").hidden = !isTabs;
      if (isTabs) Tabs.renderTabList();
    });
  }

  // First profile + its first tab (one pane, or a browser tab)
  current = profiles[0];
  buildSections(current);
  Tabs.activeProfile = current.name;
  if (current.browser) Tabs.openBrowserTab(current.url, current.name);
  else if (current.filebrowser) Tabs.openFileBrowserTab(current.start_path || "~", current.name);
  else await Tabs.newTab(current.name);
})();
