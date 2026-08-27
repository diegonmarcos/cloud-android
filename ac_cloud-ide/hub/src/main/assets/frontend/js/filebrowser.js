// filebrowser.js — yazi-style miller columns. One Transport.listDir() round
// trip per level, no client-side fs caching/watching — simplest thing that
// works. Goes through Transport (not raw Tauri invoke) so it also works on
// the Android WebView / WebSocket path.
const FileBrowser = {
  mount(container, startPath) {
    container.innerHTML = `
      <div class="fb-wrap">
        <div class="fb-addr"><input class="fb-addr-input" type="text" spellcheck="false" /></div>
        <div class="fb-cols"></div>
      </div>`;
    const addr = container.querySelector(".fb-addr-input");
    const cols = container.querySelector(".fb-cols");
    const state = { columns: [], lastClicked: null };

    const join = (base, name) => (base.endsWith("/") ? base : base + "/") + name;

    // Open a FILE entry in a File Editor tab (dir entries navigate via click).
    const openFile = (full) => Tabs.openFileEditorTab(full);

    const render = () => {
      cols.innerHTML = "";
      state.columns.forEach((col, i) => {
        const colEl = document.createElement("div");
        colEl.className = "fb-col";
        col.entries.forEach((ent) => {
          const li = document.createElement("div");
          li.className = "fb-entry" + (ent.is_dir ? " dir" : "") + (col.selected === ent.name ? " sel" : "");
          li.textContent = ent.name + (ent.is_dir ? "/" : "");
          li.addEventListener("click", () => selectEntry(i, ent));
          if (!ent.is_dir) li.addEventListener("dblclick", () => openFile(join(col.path, ent.name)));
          colEl.appendChild(li);
        });
        cols.appendChild(colEl);
      });
      cols.scrollLeft = cols.scrollWidth;
    };

    const selectEntry = async (colIndex, ent) => {
      const col = state.columns[colIndex];
      col.selected = ent.name;
      const full = join(col.path, ent.name);
      state.lastClicked = full;
      state.columns = state.columns.slice(0, colIndex + 1);
      addr.value = full;
      if (ent.is_dir) {
        try {
          const entries = await Transport.listDir(full);
          state.columns.push({ path: full, entries, selected: null });
        } catch (e) { console.error("listDir failed", e); }
      }
      render();
      container.focus();
    };

    const openPath = async (path) => {
      try {
        const entries = await Transport.listDir(path);
        state.columns = [{ path, entries, selected: null }];
        state.lastClicked = null;
        addr.value = path;
        render();
      } catch (e) { console.error("listDir failed", e); }
    };

    addr.addEventListener("keydown", (e) => {
      if (e.key === "Enter") openPath(addr.value.trim());
    });

    container.addEventListener("keydown", (e) => {
      if (e.ctrlKey && e.altKey && e.code === "KeyC" && state.lastClicked) {
        navigator.clipboard.writeText(state.lastClicked);
      }
    });
    container.tabIndex = -1; // make it a keydown target without stealing tab order

    openPath(startPath).then(() => container.focus());
    return { currentPath: () => addr.value };
  },
};
