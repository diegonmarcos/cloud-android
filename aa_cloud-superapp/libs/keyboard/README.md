# libs:keyboard — self-contained keyboard provider (HeliBoard, vendored)

The Cloud-SuperApp's own keyboard. The `LatinIME` `InputMethodService` from
[HeliBoard](https://github.com/Helium314/HeliBoard) (GPL-3.0), vendored into the
single SuperApp APK so the app ships its own keyboard — no separate install.

The SuperApp is **already a launcher** (`app` declares `category.HOME`); this
module adds the **keyboard provider**. Both ship in one APK.

## Layout

| Path | Owner | Notes |
|------|-------|-------|
| `build.gradle` | **us** (hand-owned) | Only hand-maintained file. Mirrors HeliBoard's `app/build.gradle.kts` deps + toolchain. Update on every upstream bump. |
| `src/main/{java,res,assets,jni,AndroidManifest.xml}` | **upstream, verbatim** | Populated by `./build.sh sync-heliboard`. Zero drift — never hand-edit; fix upstream or the sync step. |

## Vendoring — `./build.sh sync-heliboard`

```bash
git clone https://github.com/Helium314/HeliBoard ~/git/cloud-unix/ea_keyboard-heliboard
cd ~/git/cloud-unix/ea_cloud-superapp
./build.sh sync-heliboard      # rsync mirror app/src/main → libs/keyboard/src/main
git -C . status -s -- libs/keyboard/   # review the vendored diff, then commit
```

The sibling clone (`~/git/cloud-unix/ea_keyboard-heliboard/`) is gitignored — the
`ea_*-*` workspace-clone convention, same as `libs:net`'s
`ea_net-wireguard/`. Pin a specific tag in the clone for reproducibility.

## Manifest reconciliation (in `app/`, not here)

The vendored manifest is a verbatim upstream mirror — it declares HeliBoard's
own `<application android:name=…App>`, makes `SettingsActivity` a **LAUNCHER**
entry and `SpellCheckerSettingsActivity` a **MAIN** entry, and registers a
spellchecker service, two content providers, boot receivers, and `<queries>`.

All SuperApp-specific reconciliation lives in `app/src/main/AndroidManifest.xml`
as explicit `tools:` overlays (so this module stays a pure mirror):

- `tools:replace="android:name"` on `<application>` → the SuperApp's `.App`
  wins the merge over `helium314.keyboard.latin.App`.
- `tools:node="remove"` on the `SettingsActivity` LAUNCHER filter and the
  `SpellCheckerSettingsActivity` MAIN filter → **no extra launcher icons**.

### ⚠️ App-class init (verify first on the first build)

Because `.App` wins the merge, **HeliBoard's `App.onCreate()` never runs**. AOSP
`LatinIME` does most of its own setup in `onCreate`, so the IME likely works
regardless — but if the keyboard misbehaves (no suggestions, theme/prefs not
loading, crash on first key), port HeliBoard's `App.onCreate()` initialization
into `app/src/main/java/com/diegonmarcos/superapp/App.kt`. This is the #1
integration item to confirm against a real build.

### Provider authority collision

HeliBoard's content providers use fixed `@string` authorities
(`clipboard_provider_authority`, `gesture_data_provider_authority`). If the user
*also* has standalone HeliBoard installed, the install fails on duplicate
authority. Acceptable for a self-contained super-app; if it bites, override the
authority strings via the app source set.

## Swipe / glide typing — FOSS base + in-app loader

Glide needs Google's proprietary `libjni_latinimegoogle.so`, which is **never
bundled** (keeps the APK FOSS). HeliBoard's built-in
**Settings → Gesture typing → "Load gesture typing library"** is the in-app
loader: the user fetches the blob once at runtime into app data. Without it,
HeliBoard's open-source swipe path applies (rougher than Gboard).

## Native build

`src/main/jni/Android.mk` → `libjni_latinime.so` via `ndk-build` (NDK 28,
arm64-v8a). The SuperApp build already ships NDK modules (`libs:net` via CMake);
this is the second native module. The two NDKs (26.1 for net, 28 for keyboard)
are both provisioned in `flake.nix`.

## Toolchain note

Vendoring HeliBoard `main` forced a repo-wide toolchain bump (recorded in
`build.json::toolchain._doc_heliboard_bump`): nixpkgs 24.11→25.05, Kotlin
1.9.24→2.3.20, AGP 8.7.3→8.13.2, Gradle→8.14, compileSdk 35→36, NDK +28,
and the Kotlin-2.0 Compose-compiler plugin (`app`, `libs:wallet`, `libs:health`
dropped `composeOptions.kotlinCompilerExtensionVersion`).

## Tests (the proof — run after `sync-heliboard`)

1. **Build gate**
   ```bash
   ./build.sh build
   unzip -l dist/*.apk | grep -E 'libjni_latinime\.so'   # native decoder present
   unzip -l dist/*.apk | grep -vq 'libjni_latinimegoogle\.so' && echo "blob NOT bundled (FOSS) ✓"
   ```
2. **Single-launcher gate** — install, confirm exactly **one** launcher icon
   (the SuperApp); HeliBoard Settings / Spell-checker do **not** appear as
   separate icons.
3. **Provider gate** — Settings → Languages → Keyboards lists the SuperApp
   keyboard; enable + select it; type into the SuperApp search field.
4. **Swipe gate** — Keyboard settings → load gesture library → swipe a word →
   it resolves.
5. **Regression gate** — SuperApp still launches as Home; APK size delta within
   the Compose-inclusive estimate (~+10–15 MB over the pre-keyboard ~7.8 MB).
