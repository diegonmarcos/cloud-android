# Wallet 3D View — Design Orientations

## View Modes

The IDs tab has a **three-mode segmented toggle** `[2D | 3D-r | 3D-f]`:

| Mode | Code key | Technology | Status |
|------|----------|------------|--------|
| Flat Compose cards | `2d` | Jetpack Compose (current) | Live |
| React Three Fiber (WebView) | `3dr` | R3F + Three.js WebGPU inside `WebView` | Stub |
| Filament native | `3df` | Google Filament via SceneView/SceneCore (Vulkan/OpenGL) | Stub |

Dispatch in `WalletDeckUi.kt → WalletIdsTab`:
```kotlin
"3dr" -> IdCard3DReactView(card, modifier)
"3df" -> IdCard3DFilamentView(card, modifier)
else  -> IdCardView(card, modifier)          // 2D
```

---

## 3D-r: React Three Fiber via WebView

### When to use
Cross-platform teams already running a web app, or rapid prototyping. **Not recommended for production native Android** — the JS engine overhead drains battery and complicates native UI composition.

WebGPU is fully supported in the modern Android System WebView, so R3F code runs — but always inside a browser sandbox.

### Implementation pattern
The `IdCard3DReactView` composable wraps an `AndroidView(::WebView)` that loads an HTML asset from `assets/wallet3d/index.html`. The card type is passed via `WebView.evaluateJavascript("setCard('${card.kind}')")` or as a URL fragment (`?card=id_passport_es`).

### R3F PBR technique (from provided implementation)

**Procedural studio HDRI** — single `CanvasTexture` (512×256) with three soft-box light blobs, set as `scene.environment` for reflections.

**Voronoi leather noise** — CPU-generated `CanvasTexture` for passport cover bump/roughness. Cached (one canvas reused across remounts).

**PBR map set per document** — five canvas textures per view:
- `colorMap` — diffuse / albedo
- `bumpMap` — height/normal stand-in
- `metalMap` — metalness (chip, foil text = white; paper = black)
- `roughMap` — roughness (polycarbonate = near-0; paper = 0.8+)
- `iriMap` — iridescence mask for holographic overlays (DNI chip area, passport id-page kinegram)

**Material per document type**:
- **Passport cover**: `MeshPhysicalMaterial` — voronoi bump, foil text via `metalness=1.0 / roughness=0.2` mask, `clearcoat=0.1`
- **DNI / polycarbonate card**: `MeshPhysicalMaterial` with `iridescence=1.0`, `iridescenceMap`, `clearcoat=1.0` → hologram overlay
- **Passport spread (info/stamps)**: paper PBR — `roughness=0.8`, page-crease bump gradient at `x=w/2`, guilloche line overlay
- **Stamp page**: `globalCompositeOperation='multiply'` for ink-on-paper look

**Tilt interaction**: `useRef({ x:0, y:0 })` updated on `mousemove`/`touchmove`; `useFrame` lerps `mesh.rotation` at `delta*12` for silky physics.

**Geometry**: `BoxGeometry` with 6 separate materials (right/left/top/bottom/front/back) — page edges use a procedurally generated stripe texture.

### Asset pipeline
- **3D mesh**: Generic blank passport `.glb` from **Sketchfab** ("Passport downloadable") or **CGTrader** ("Blank Passport PBR", UV-unwrapped). Convert `.fbx`/`.blend` → `.glb` via Blender.
- **Cover textures**: **Freepik / Adobe Stock** — Spanish burgundy + Brazilian dark-blue leather covers with coat-of-arms + gold foil text (`spain_cover.jpg`, `brazil_cover.jpg`)
- **Coats of arms**: **Wikimedia Commons** — high-res SVG of Spanish coat of arms + Brazilian coat of arms (public domain)
- **Synthetic id pages (Brazil)**: **Kaggle** — "Synthetic Printed Brazilian Passports" dataset (AI-generated, privacy-safe)
- Single blank `.glb` + per-country `TextureLoader` swap → supports N countries without N separate models

---

## 3D-f: Filament Native (Kotlin / Jetpack Compose)

### Why this is the right choice for native Android
- **Zero JS engine overhead** — renders directly via Vulkan/OpenGL, runs at 120 fps
- **Native UI composition** — Compose overlays layer cleanly over the 3D surface with no bridging lag
- **First-class `.glb` support** — Filament loads glTF Binary natively
- **PBR parity** — identical material model to Three.js (metalness-roughness, iridescence, clearcoat)

### Library
**SceneView** (`io.github.sceneview:sceneview`) or the newer **Jetpack SceneCore** (`androidx.xr.scenecore`) wraps Filament into Compose-friendly composables.

```kotlin
// SceneView Compose wrapper
Scene(
    modifier = Modifier.fillMaxSize(),
    onCreate = { sceneView ->
        sceneView.loadModelGlb("models/blank_passport.glb") { model ->
            // swap cover texture per country
            model.meshes.find { it.name == "Cover" }
                ?.material?.setTexture("baseColorMap", countryTexture)
        }
    }
)
```

### Shader / material mapping
| Feature | Three.js | Filament |
|---------|----------|----------|
| Leather cover | `MeshPhysicalMaterial` + voronoi bump | Standard PBR `.mat` + baked normal map |
| Gold foil text | `metalness=1.0, roughness=0.2` on mask | `metallic=1.0, roughness=0.2` via mask texture |
| DNI hologram | `iridescence=1.0` + `iridescenceMap` | `iridescence` property on `MeshPhysicalMaterial` Filament variant |
| Passport paper | `roughness=0.8, clearcoat=0` | Standard roughness + no clearcoat |

Filament **does not use WGSL** — materials are authored in Filament's `.mat` format (GLSL-like) and compiled to platform-optimal bytecode (SPIRV/Metal/OpenGL).

### Asset pipeline (same as 3D-r)
One blank passport `.glb` in `app/src/main/assets/models/`. Per-country covers loaded at runtime via `TextureLoader`. Coat-of-arms SVG → PNG baked into the texture atlas.

---

## Composite flow

```
WalletIdsTab
  ├── viewMode == "2d"  → IdCardView(card)            [Compose flat]
  ├── viewMode == "3dr" → IdCard3DReactView(card)     [WebView + R3F]
  └── viewMode == "3df" → IdCard3DFilamentView(card)  [Filament native]
```

`IdCard3DReactView` and `IdCard3DFilamentView` live in `WalletIdCardUi.kt`. Replace their stub bodies with the real implementations when ready.
