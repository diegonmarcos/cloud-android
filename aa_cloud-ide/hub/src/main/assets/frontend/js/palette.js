// palette.js — vim-style ":" command palette. Searches every profile's
// command items at once, grouped by profile; groups with no match still
// render (labeled "no matches") so the full profile list stays visible.
const Palette = {
  profiles: [],
  runItem: null,
  highlight: 0,

  isOpen() { return !document.getElementById("palette").hidden; },

  open() {
    const el = document.getElementById("palette");
    const input = document.getElementById("palette-input");
    el.hidden = false;
    input.value = ":";
    input.focus();
    this.highlight = 0;
    this.render();
  },

  close() {
    document.getElementById("palette").hidden = true;
  },

  render() {
    const q = document.getElementById("palette-input").value.replace(/^:/, "").trim().toLowerCase();
    const host = document.getElementById("palette-results");
    host.innerHTML = "";
    const flat = [];
    for (const p of this.profiles) {
      const items = [];
      for (const sec of p.sections || []) {
        for (const item of sec.items || []) {
          const hay = (item.label + " " + (item.cmd || "")).toLowerCase();
          if (!q || hay.includes(q)) items.push(item);
        }
      }
      const group = document.createElement("div");
      group.className = "palette-group";
      const title = document.createElement("div");
      title.className = "palette-group-title";
      title.textContent = p.display_name || p.name;
      group.appendChild(title);
      if (items.length === 0) {
        const none = document.createElement("div");
        none.className = "palette-none";
        none.textContent = "no matches";
        group.appendChild(none);
      } else {
        for (const item of items) {
          const row = document.createElement("div");
          row.className = "palette-item";
          row.textContent = item.label;
          const idx = flat.length;
          row.addEventListener("click", () => this.run(item));
          row.addEventListener("mouseenter", () => this.setHighlight(idx));
          group.appendChild(row);
          flat.push({ el: row, item });
        }
      }
      host.appendChild(group);
    }
    this._flat = flat;
    this.setHighlight(Math.min(this.highlight, Math.max(flat.length - 1, 0)));
  },

  setHighlight(i) {
    if (!this._flat) return;
    this._flat.forEach((f) => f.el.classList.remove("sel"));
    this.highlight = i;
    const f = this._flat[i];
    if (f) { f.el.classList.add("sel"); f.el.scrollIntoView({ block: "nearest" }); }
  },

  move(d) {
    if (!this._flat || this._flat.length === 0) return;
    this.setHighlight((this.highlight + d + this._flat.length) % this._flat.length);
  },

  run(item) {
    this.close();
    if (this.runItem) this.runItem(item);
  },

  runHighlighted() {
    const f = this._flat && this._flat[this.highlight];
    if (f) this.run(f.item);
  },
};

document.getElementById("palette-input").addEventListener("input", () => Palette.render());
document.getElementById("palette-input").addEventListener("keydown", (e) => {
  if (e.key === "Escape") { Palette.close(); return; }
  if (e.key === "Enter") { e.preventDefault(); Palette.runHighlighted(); return; }
  if (e.key === "ArrowDown") { e.preventDefault(); Palette.move(+1); return; }
  if (e.key === "ArrowUp") { e.preventDefault(); Palette.move(-1); return; }
});

// Bare ":" opens the palette — but only when a terminal pane isn't the
// target, so normal shell/vim usage of ":" is never intercepted.
window.addEventListener("keydown", (e) => {
  if (Palette.isOpen()) return;
  if (e.key !== ":" || e.ctrlKey || e.altKey || e.metaKey) return;
  if (e.target.closest(".pane")) return;
  e.preventDefault();
  Palette.open();
});
