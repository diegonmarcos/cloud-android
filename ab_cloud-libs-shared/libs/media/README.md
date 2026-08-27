# libs:media — Sticker + GIF panel for the keyboard emoji surface

Adds a **row-1 type-tab strip (Emoji · Sticker · GIF)** above the vendored
keyboard's existing emoji category row (row 2), and the Sticker/GIF body behind
the new tabs. Emoji is unchanged (HeliBoard's own pager); Sticker + GIF are this
module.

Self-contained, like `libs:translate` / `libs:voice` — it lives **outside**
`libs/keyboard/src/main` so `build.sh sync-heliboard` (rsync `--delete` mirror of
upstream HeliBoard) never clobbers it.

## Wiring (who calls what)

```
App.onCreate ──▶ MediaRuntime.configure(BuildConfig.MEDIA_CONFIG_B64,
                                        BuildConfig.TENOR_API_KEY,
                                        BuildConfig.GIPHY_API_KEY)   # once, at startup
libs/keyboard patch 0007 ──▶ EmojiPalettesView hosts MediaPanelView,
     toggles bodies on tab tap, binds pick → KeyboardActionListener.onContent
MediaPanelView ──▶ GifProvider (Tenor|Giphy) / StickerRepo (WhatsApp packs)
     pick ──▶ MediaCommit (cache + FileProvider) ──▶ commitContent(GRANT_READ)
```

- **Config is data-driven** from `build.json::keyboard_media` (provider choice,
  endpoints, GIF categories, limit) → base64 → `BuildConfig.MEDIA_CONFIG_B64`.
  Flip Tenor⇄Giphy by editing `gif.provider` — no code change.
- **Stickers** = any installed app exposing a `*.stickercontentprovider`
  authority (the public WhatsApp sticker contract). Needs `QUERY_ALL_PACKAGES`,
  already declared by `app/`. One row-2 tab per pack, discovered at runtime.
- **Delivery** reuses the keyboard's existing `RichInputConnection.commitContent`
  path. Picked assets are cached under `cacheDir/keyboard_media` and served via
  the app FileProvider (`${applicationId}.mediaprovider`,
  `app/src/main/res/xml/media_file_paths.xml`).

## The one thing you must supply — GIF API key (secret)

GIFs need a provider key. **Absent key = build still succeeds**, the GIF tab just
shows *"No GIF API key configured"*; stickers + emoji are unaffected.

Put the key(s) in the vault, sops-encrypted, at the path named by
`build.json::keyboard_media.vault_secrets`
(`A0_keys/providers/keyboard-media/secrets.yaml`):

```yaml
# decrypted shape (encrypt with the vault's age key via sops before committing)
tenor_api_key: "<your Tenor v2 key>"     # console.cloud.google.com → Tenor API
giphy_api_key: "<your Giphy key>"        # developers.giphy.com
```

`build.sh` (`_resolve_media_keys`) sops-decrypts these and exports
`TENOR_API_KEY` / `GIPHY_API_KEY` into the Gradle build. CI/env vars of the same
name win over the vault (for GitHub-secret delivery).

## Tests — the proof (run after a build)

1. **Build gate** — `./build.sh build` succeeds; `libs:media` compiles and the
   APK contains Coil:
   ```bash
   unzip -l dist/*.apk | grep -qi 'coil' && echo "coil bundled ✓"
   ```
2. **Emoji-key gate** (patch 0006) — open any text field: a dedicated emoji key
   is visible on the bottom row (no longer hidden behind a comma long-press).
3. **Tab gate** (patch 0007) — tap the emoji key → a row of **Emoji · Sticker ·
   GIF** tabs shows above the categories. Tapping each switches the body.
4. **Sticker gate** — with a WhatsApp-compatible sticker app installed, the
   Sticker tab lists one row-2 tab per pack; tapping a sticker commits it into a
   receptive field (e.g. the SuperApp chat / any EditText that accepts images).
5. **GIF gate** — with a key configured, the GIF tab shows Trending; picking a
   GIF commits it. Without a key it shows the "no API key" empty state.
6. **Degrade gate** — set `keyboard_media.enabled=false` in build.json, rebuild:
   the type-tab row is gone and the emoji panel behaves exactly as before.

> Delivery caveat: `commitContent` only lands in apps that advertise accepted
> MIME types on their `EditorInfo` (`image/gif`, `image/webp`). Apps that don't
> (many plain text fields) silently ignore rich content — expected, not a bug.
