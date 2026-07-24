// configs.js — frontend Configs overlay (same app UI). Per-app settings live in
// localStorage and are read when opening the matching tab. The Terminal/SSH
// settings open the native screen via the AndroidTerm bridge.
const Configs = {
  KEYS: {
    browserUrl: "myk-cfg-browser-url",
    fbPath: "myk-cfg-fb-path",
    fePath: "myk-cfg-fe-path",
  },
  get(k, def) { try { return localStorage.getItem(k) || def; } catch (e) { return def; } },
  set(k, v) { try { if (v) localStorage.setItem(k, v); else localStorage.removeItem(k); } catch (e) {} },

  open() {
    const byId = (id) => document.getElementById(id);
    byId("cfg-browser-url").value = this.get(this.KEYS.browserUrl, "https://duckduckgo.com");
    byId("cfg-fb-path").value = this.get(this.KEYS.fbPath, "~");
    byId("cfg-fe-path").value = this.get(this.KEYS.fePath, "");
    byId("configs-overlay").hidden = false;
  },
  close() { document.getElementById("configs-overlay").hidden = true; },

  init() {
    const byId = (id) => document.getElementById(id);
    byId("cfg-close").addEventListener("click", () => this.close());
    byId("configs-overlay").addEventListener("click", (e) => { if (e.target.id === "configs-overlay") this.close(); });
    byId("cfg-browser-url").addEventListener("change", (e) => this.set(this.KEYS.browserUrl, e.target.value.trim()));
    byId("cfg-fb-path").addEventListener("change", (e) => this.set(this.KEYS.fbPath, e.target.value.trim()));
    byId("cfg-fe-path").addEventListener("change", (e) => this.set(this.KEYS.fePath, e.target.value.trim()));
    byId("cfg-ssh-open").addEventListener("click", () => {
      if (window.AndroidTerm && window.AndroidTerm.openConfigs) window.AndroidTerm.openConfigs();
      else alert("Terminal/SSH settings are available in the app build.");
    });
  },
};
window.Configs = Configs;
Configs.init();
