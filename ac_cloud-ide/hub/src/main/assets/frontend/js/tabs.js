// tabs.js — tab strip. Each tab belongs to a profile (its "group"); switching
// profile shows only that group's tabs in the strip + center pane. Each tab
// owns a `.tab-root` in #terms that holds its pane split-tree (term.js).
const Tabs = {
  tabs: new Map(),  // tabId -> { rootEl, tabEl, profile, isBrowser }
  order: [],
  active: null,
  activeProfile: null,
  lastActiveByProfile: new Map(),
  seq: 0,

  // ── Drag-to-reorder (Konsole-style): click-hold + drag a tab to move it.
  // Native HTML5 DnD — no library. Works on both the horizontal strip tabEl
  // and the vertical sidebar row for the same tabId. Drop position (left/right
  // half for the strip, top/bottom half for the vertical list) decides
  // before/after; reorderTo() rebuilds this.order + both DOM listings.
  _bindDrag(el, tabId, vertical = false) {
    el.draggable = true;
    el.addEventListener("dragstart", (e) => {
      e.dataTransfer.setData("text/plain", tabId);
      e.dataTransfer.effectAllowed = "move";
      el.classList.add("dragging");
    });
    el.addEventListener("dragend", () => el.classList.remove("dragging"));
    el.addEventListener("dragover", (e) => {
      e.preventDefault();
      const r = el.getBoundingClientRect();
      const after = vertical ? e.clientY - r.top > r.height / 2 : e.clientX - r.left > r.width / 2;
      el.classList.toggle("drag-over-after", after);
      el.classList.toggle("drag-over-before", !after);
    });
    el.addEventListener("dragleave", () => el.classList.remove("drag-over-after", "drag-over-before"));
    el.addEventListener("drop", (e) => {
      e.preventDefault();
      el.classList.remove("drag-over-after", "drag-over-before");
      const draggedId = e.dataTransfer.getData("text/plain");
      const r = el.getBoundingClientRect();
      const after = vertical ? e.clientY - r.top > r.height / 2 : e.clientX - r.left > r.width / 2;
      this.reorderTo(draggedId, tabId, after);
    });
  },

  reorderTo(draggedId, targetId, after) {
    if (draggedId === targetId || !this.tabs.has(draggedId) || !this.tabs.has(targetId)) return;
    this.order = this.order.filter((id) => id !== draggedId);
    let idx = this.order.indexOf(targetId);
    if (after) idx++;
    this.order.splice(idx, 0, draggedId);
    this._syncStripOrder();
    this.renderTabList();
  },

  _syncStripOrder() {
    const strip = document.getElementById("tabstrip"), btn = document.getElementById("btn-newtab");
    for (const id of this.order) if (this.tabs.has(id)) strip.insertBefore(this.tabs.get(id).tabEl, btn);
  },

  // ── Wire the strip's tabEl: click/close, right-click group menu, drag ──
  _wireTabEl(tabEl, tabId) {
    tabEl.addEventListener("click", (e) => {
      if (e.target.classList.contains("tab-close")) { this.close(tabId); return; }
      this.activate(tabId);
    });
    tabEl.addEventListener("contextmenu", (e) => {
      e.preventDefault();
      this.showTabMenu(tabId, e.clientX, e.clientY);
    });
    this._bindDrag(tabEl, tabId);
  },

  // ── Tab grouping: cluster tabs under a named, color-coded group. Purely
  // organizational (no isolation) — group tabs sit adjacent in both the
  // strip and the vertical sidebar list, tagged with a deterministic color.
  groupColor(name) {
    if (!name) return "transparent";
    let h = 0;
    for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) % 360;
    return `hsl(${h}, 65%, 55%)`;
  },

  setGroup(tabId, name) {
    const t = this.tabs.get(tabId);
    if (!t) return;
    t.group = name || null;
    t.tabEl.style.borderLeftColor = this.groupColor(t.group);
    if (t.group) {
      const mates = this.order.filter((id) => id !== tabId && this.tabs.get(id)?.profile === t.profile && this.tabs.get(id)?.group === t.group);
      if (mates.length) {
        this.order = this.order.filter((id) => id !== tabId);
        this.order.splice(this.order.indexOf(mates[mates.length - 1]) + 1, 0, tabId);
      }
    }
    this._syncStripOrder();
    this.renderTabList();
  },

  showTabMenu(tabId, x, y) {
    document.getElementById("tab-menu")?.remove();
    const t = this.tabs.get(tabId);
    if (!t) return;
    const menu = document.createElement("div");
    menu.id = "tab-menu";
    const items = [
      [t.group ? "Rename Group…" : "New Group…", () => {
        const name = prompt("Group name:", t.group || "");
        if (name !== null) this.setGroup(tabId, name.trim());
      }],
    ];
    if (t.group) items.push(["Remove from Group", () => this.setGroup(tabId, null)]);
    items.push(["Close Tab", () => this.close(tabId)]);
    for (const [label, fn] of items) {
      const it = document.createElement("div");
      it.className = "pane-menu-item";
      it.textContent = label;
      it.addEventListener("click", () => { menu.remove(); fn(); });
      menu.appendChild(it);
    }
    document.body.appendChild(menu);
    const r = menu.getBoundingClientRect();
    menu.style.left = Math.min(x, window.innerWidth - r.width - 4) + "px";
    menu.style.top = Math.min(y, window.innerHeight - r.height - 4) + "px";
    const closeOnce = (e) => { if (!menu.contains(e.target)) menu.remove(); };
    setTimeout(() => document.addEventListener("mousedown", closeOnce, { once: true }), 0);
  },

  // ── Vertical tab list (sidebar "Tabs" view): current profile's tabs,
  // clustered by group (ungrouped last), draggable to reorder same as strip.
  renderTabList() {
    const host = document.getElementById("tabs-panel");
    if (!host) return;
    host.innerHTML = "";
    const ids = this._group();
    const byGroup = new Map();
    for (const id of ids) {
      const g = this.tabs.get(id)?.group || null;
      if (!byGroup.has(g)) byGroup.set(g, []);
      byGroup.get(g).push(id);
    }
    const keys = [...byGroup.keys()].sort((a, b) => (a === null) - (b === null));
    for (const g of keys) {
      if (g) {
        const h = document.createElement("div");
        h.className = "tabpanel-group-title";
        h.style.borderLeftColor = this.groupColor(g);
        h.textContent = g;
        host.appendChild(h);
      }
      for (const id of byGroup.get(g)) {
        const t = this.tabs.get(id);
        const row = document.createElement("div");
        row.className = "tabpanel-item" + (id === this.active ? " active" : "");
        row.dataset.id = id;
        row.style.borderLeftColor = g ? this.groupColor(g) : "transparent";
        row.innerHTML = `<span class="tab-icon">${t.icon}</span><span class="tabpanel-title">${t.tabEl.querySelector(".tab-title").textContent}</span><span class="tab-close">✕</span>`;
        row.addEventListener("click", (e) => {
          if (e.target.classList.contains("tab-close")) { this.close(id); return; }
          this.activate(id);
        });
        row.addEventListener("contextmenu", (e) => { e.preventDefault(); this.showTabMenu(id, e.clientX, e.clientY); });
        this._bindDrag(row, id, true);
        host.appendChild(row);
      }
    }
  },

  async newTab(profile = this.activeProfile) {
    const tabId = "T" + ++this.seq;
    const rootEl = document.createElement("div");
    rootEl.className = "tab-root";
    rootEl.dataset.id = tabId;
    document.getElementById("terms").appendChild(rootEl);

    const tabEl = document.createElement("div");
    tabEl.className = "tab"; tabEl.dataset.id = tabId;
    tabEl.innerHTML = `<span class="tab-icon">💻</span><span class="tab-title">shell</span><span class="tab-close">✕</span>`;
    this._wireTabEl(tabEl, tabId);
    document.getElementById("tabstrip").insertBefore(tabEl, document.getElementById("btn-newtab"));

    this.tabs.set(tabId, { rootEl, tabEl, profile, icon: "💻", group: null });
    this.order.push(tabId);
    const paneId = await MYK.makePane(tabId, rootEl);
    this.activate(tabId);
    MYK.focusPane(paneId);
    return tabId;
  },

  // ── Browser tab: iframe + editable address bar, no PTY. Reuses the OS
  // webview engine (WebKitGTK) — no bundled Chromium, no extra deps.
  openBrowserTab(url = "https://duckduckgo.com", profile = this.activeProfile) {
    const tabId = "T" + ++this.seq;
    if (window.AndroidTerm && window.AndroidTerm.browserOpen) window.AndroidTerm.browserOpen(url);
    const rootEl = document.createElement("div");
    rootEl.className = "tab-root";
    rootEl.dataset.id = tabId;
    rootEl.innerHTML = `
      <div class="browser-wrap">
        <div class="browser-addr"><input class="browser-addr-input" type="text" spellcheck="false" value="${url}" /></div>
        <iframe class="browser-frame" src="${url}"></iframe>
      </div>`;
    document.getElementById("terms").appendChild(rootEl);

    const addr = rootEl.querySelector(".browser-addr-input");
    const frame = rootEl.querySelector(".browser-frame");
    addr.addEventListener("keydown", (e) => {
      if (e.key !== "Enter") return;
      let v = addr.value.trim();
      if (!/^[a-z]+:\/\//i.test(v)) v = "https://" + v;
      addr.value = v;
      frame.src = v;
    });

    const tabEl = document.createElement("div");
    tabEl.className = "tab"; tabEl.dataset.id = tabId;
    tabEl.innerHTML = `<span class="tab-icon">🌐</span><span class="tab-title">Browser</span><span class="tab-close">✕</span>`;
    this._wireTabEl(tabEl, tabId);
    document.getElementById("tabstrip").insertBefore(tabEl, document.getElementById("btn-newtab"));

    this.tabs.set(tabId, { rootEl, tabEl, profile, isBrowser: true, icon: "🌐", group: null });
    this.order.push(tabId);
    this.activate(tabId);
    return tabId;
  },

  // ── File browser tab: yazi-style miller columns, no PTY.
  openFileBrowserTab(startPath, profile = this.activeProfile) {
    const tabId = "T" + ++this.seq;
    const rootEl = document.createElement("div");
    rootEl.className = "tab-root";
    rootEl.dataset.id = tabId;
    document.getElementById("terms").appendChild(rootEl);
    FileBrowser.mount(rootEl, startPath);

    const tabEl = document.createElement("div");
    tabEl.className = "tab"; tabEl.dataset.id = tabId;
    tabEl.innerHTML = `<span class="tab-icon">📁</span><span class="tab-title">Files</span><span class="tab-close">✕</span>`;
    this._wireTabEl(tabEl, tabId);
    document.getElementById("tabstrip").insertBefore(tabEl, document.getElementById("btn-newtab"));

    this.tabs.set(tabId, { rootEl, tabEl, profile, isFileBrowser: true, icon: "📁", group: null });
    this.order.push(tabId);
    this.activate(tabId);
    return tabId;
  },

  // ── File editor tab: plain textarea editor, no PTY. ponytail: syntax
  // highlighting later — this is deliberately just a <textarea>, no
  // CodeMirror/Monaco dependency.
  openFileEditorTab(path, profile = this.activeProfile) {
    const tabId = "T" + ++this.seq;
    const rootEl = document.createElement("div");
    rootEl.className = "tab-root";
    rootEl.dataset.id = tabId;
    rootEl.innerHTML = `
      <div class="editor-wrap">
        <div class="editor-addr">
          <input class="editor-path-input" type="text" spellcheck="false" value="${path || ""}" />
          <button class="editor-reload" title="Reload">⟳</button>
          <button class="editor-save" title="Save">Save</button>
        </div>
        <textarea class="editor-area" spellcheck="false"></textarea>
      </div>`;
    document.getElementById("terms").appendChild(rootEl);

    const pathInput = rootEl.querySelector(".editor-path-input");
    const area = rootEl.querySelector(".editor-area");
    const load = async (p) => {
      if (!p) return;
      try { area.value = await Transport.readFile(p); }
      catch (e) { console.error("readFile failed", e); }
    };
    rootEl.querySelector(".editor-reload").addEventListener("click", () => load(pathInput.value.trim()));
    rootEl.querySelector(".editor-save").addEventListener("click", async () => {
      try { await Transport.writeFile(pathInput.value.trim(), area.value); console.log("saved", pathInput.value.trim()); }
      catch (e) { console.error("writeFile failed", e); }
    });

    const tabEl = document.createElement("div");
    tabEl.className = "tab"; tabEl.dataset.id = tabId;
    const title = path ? (path.split("/").filter(Boolean).pop() || path) : "untitled";
    tabEl.innerHTML = `<span class="tab-icon">📝</span><span class="tab-title">${title}</span><span class="tab-close">✕</span>`;
    this._wireTabEl(tabEl, tabId);
    document.getElementById("tabstrip").insertBefore(tabEl, document.getElementById("btn-newtab"));

    this.tabs.set(tabId, { rootEl, tabEl, profile, isFileEditor: true, icon: "📝", group: null });
    this.order.push(tabId);
    this.activate(tabId);
    if (path) load(path);
    return tabId;
  },

  activate(tabId) {
    if (!this.tabs.has(tabId)) return;
    this.active = tabId;
    const profile = this.tabs.get(tabId).profile;
    this.activeProfile = profile;
    this.lastActiveByProfile.set(profile, tabId);
    for (const [tid, t] of this.tabs) {
      const on = tid === tabId;
      t.rootEl.classList.toggle("active", on);
      t.tabEl.classList.toggle("active", on);
    }
    const first = this.tabs.get(tabId).rootEl.querySelector(".pane");
    if (first) MYK.focusPane(first.dataset.id);
    this.renderTabList();
  },

  // Switch to a profile's tab group: show only its tabs in the strip,
  // resume its last-active tab, or open a fresh one if it has none yet.
  switchProfile(p) {
    const name = p.name;
    if (this.activeProfile === name) return;
    this.activeProfile = name;
    for (const [, t] of this.tabs) t.tabEl.style.display = t.profile === name ? "" : "none";

    const last = this.lastActiveByProfile.get(name);
    if (last && this.tabs.has(last)) { this.activate(last); return; }
    if (p.browser) this.openBrowserTab((window.Configs && Configs.get(Configs.KEYS.browserUrl)) || p.url, name);
    else if (p.filebrowser) this.openFileBrowserTab((window.Configs && Configs.get(Configs.KEYS.fbPath)) || p.start_path || "~", name);
    else if (p.fileeditor) this.openFileEditorTab((window.Configs && Configs.get(Configs.KEYS.fePath)) || null, name);
    else this.newTab(name);
  },

  setTitle(tabId, title) {
    const t = this.tabs.get(tabId);
    if (t && title) t.tabEl.querySelector(".tab-title").textContent = title;
    this.renderTabList();
  },

  close(tabId) {
    if (!this.tabs.has(tabId)) return;
    const profile = this.tabs.get(tabId).profile;
    MYK.disposeTab(tabId);
    const t = this.tabs.get(tabId);
    t.rootEl.remove(); t.tabEl.remove();
    this.tabs.delete(tabId);
    this.order = this.order.filter((x) => x !== tabId);

    const remaining = this.order.filter((id) => this.tabs.get(id)?.profile === profile);
    if (remaining.length === 0) { this.newTab(profile); return; }
    if (this.active === tabId) this.activate(remaining[remaining.length - 1]);
  },

  // ── Session snapshot: dump {profile, kind, url/startPath} per tab to
  // localStorage. No cwd tracking for shell tabs — restoring just opens a
  // fresh shell in that profile (ponytail: good enough, not a full state dump).
  saveSession() {
    const snap = this.order.map((id) => {
      const t = this.tabs.get(id);
      if (t.isBrowser) return { profile: t.profile, kind: "browser", url: t.rootEl.querySelector(".browser-addr-input")?.value };
      if (t.isFileBrowser) return { profile: t.profile, kind: "filebrowser", startPath: t.rootEl.querySelector(".fb-addr-input")?.value };
      if (t.isFileEditor) return { profile: t.profile, kind: "fileeditor", path: t.rootEl.querySelector(".editor-path-input")?.value };
      return { profile: t.profile, kind: "shell" };
    });
    localStorage.setItem("myk-session", JSON.stringify(snap));
  },

  async restoreSession() {
    let snap;
    try { snap = JSON.parse(localStorage.getItem("myk-session") || "[]"); } catch { snap = []; }
    if (snap.length === 0) return;
    for (const t of snap) {
      if (t.kind === "browser") this.openBrowserTab(t.url, t.profile);
      else if (t.kind === "filebrowser") this.openFileBrowserTab(t.startPath || "~", t.profile);
      else if (t.kind === "fileeditor") this.openFileEditorTab(t.path || null, t.profile);
      else await this.newTab(t.profile);
    }
  },

  next() { this._step(+1); },
  prev() { this._step(-1); },
  _group() { return this.order.filter((id) => this.tabs.get(id)?.profile === this.activeProfile); },
  _step(d) {
    const group = this._group();
    const i = group.indexOf(this.active);
    if (i < 0) return;
    this.activate(group[(i + d + group.length) % group.length]);
  },

  // Move the active tab left/right within its profile group.
  move(d) {
    const group = this._group();
    const i = group.indexOf(this.active);
    const j = i + d;
    if (i < 0 || j < 0 || j >= group.length) return;
    [group[i], group[j]] = [group[j], group[i]];
    const firstIdx = this.order.findIndex((id) => this.tabs.get(id)?.profile === this.activeProfile);
    this.order = this.order.filter((id) => this.tabs.get(id)?.profile !== this.activeProfile);
    this.order.splice(firstIdx, 0, ...group);
    const strip = document.getElementById("tabstrip"), btn = document.getElementById("btn-newtab");
    group.forEach((id) => strip.insertBefore(this.tabs.get(id).tabEl, btn));
    this.renderTabList();
  },
};
