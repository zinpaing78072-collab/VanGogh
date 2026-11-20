# Themes

The app ships two skins — **Starry Night** (default) and **Liquid Neon** — and a
theme is a CSS file, not a mode in the code. This page explains how the layers
fit and the exact steps for adding a third.

Design record: `docs/superpowers/specs/2026-11-19-theme-switching-design.md`.

## The files

| File | Owns |
|---|---|
| `base.css` | Structure, geometry, box model, skeletons, focus rings, reduced motion — **and the role tokens' default values** (Starry Night's). |
| `theme-starry.css` | Starry Night: `starry.png` backdrop, the frosted veil, the `@supports` glass fallback, and the header offsets that place the Search button and the AI toggle. Small on purpose. |
| `theme-liquidneon.css` | Liquid Neon: `liquidneon.png` backdrop, scanline veil, mono type, HUD corners, flat fills, its own header metrics. |
| `music-detail.css` | Song-detail layout. Structure only — no palette literals, role tokens only. |
| `settings.css` | Settings layout. Same rule. |

`index.html` loads them in that order, with the theme sheet **last**:

```html
<link rel="stylesheet" href="base.css">
<link rel="stylesheet" href="music-detail.css">
<link rel="stylesheet" href="settings.css">
<link rel="stylesheet" id="themeSkin" href="theme-starry.css">
```

Last wins the cascade, so a theme never needs `!important`. `<html
data-theme="starry">` mirrors the loaded sheet — it is a marker for scripts and
tests, not a selector prefix, so theme files stay plain override sheets.

## The token contract

Everything outside a theme file references **role tokens** only:

```
--bg --panel --panel-hover --panel-inset --panel-deep
--panel-firm-a45 … a88        (denser glass: song tiles, Settings card)
--accent --accent-hover --accent-ink
--accent-a05 … a90 --accent-hover-a22 … a55   (accent tints by alpha)
--muted-a10 … a70                             (border/fill tints)
--text --text-soft --text-soft-strong --text-soft-dim
--text-muted --label --label-soft --placeholder --rating --danger
--border --border-strong --focus
--inset-focus --scroll-track --placeholder-from
--blur-glass --radius --mono
```

`base.css` declares all of them in `:root` with Starry values, so a missing or
broken theme link still paints a coherent page. A theme overrides the roles it
changes and declares its own private palette *inside its own file* (Liquid Neon
keeps `--neon-pink`, `--neon-cyan`, `--night-0/2` for that reason). Private names
must never leak into a shared sheet: `var(--undefined)` does not fall back, it
invalidates the declaration.

## What a theme sheet may change

1. **Role tokens** — the palette.
2. **Rules whose shape differs** — type scale, radii, letter-spacing, uppercase,
   backdrop treatment, the `.sky` / `.veil` layers, the header offsets.
3. **Decorations base never had** — HUD corner brackets, glows, scanlines.

CSS has no per-property reset, so a theme that wants a base shadow or gradient
gone must say so: `box-shadow: none`, `background-image: none`. Every droplet of
Liquid Neon's flat look is one of those explicit null-outs — keep that invariant
when editing, or a stray gold glow reappears in the neon skin.

## Adding theme #3

1. **Write `theme-<id>.css`.** The id is lowercase alphanumerics
   (`^[a-z][a-z0-9]*$`, asserted by `ThemeConfigTest`); the filename *is* the
   mapping. Start from `:root { … }` overriding the roles, then add only the
   shape rules that differ. Ship a `theme-<id>.png` backdrop if the theme has
   one and put the `.sky` / `.veil` rules here.
2. **Add one row to `ThemeConfig.KNOWN`** in
   `src/main/java/vangogh/theme/ThemeConfig.java`:
   `new Choice("<id>", "Display Name")`. That list is the only registry — the
   Settings chips are rendered from the `loadTheme` reply, so nothing in JS or
   HTML knows a theme exists.
3. **Nothing else.** No JS edit, no HTML edit, no controller change: the
   `theme-<id>.css` convention plus the registry row is the whole wiring.
4. **Cover it in the harness.** `?theme=<id>` in `preview/mock-krema.js` already
   works for any id in its `THEMES` list — add the row there too (it mirrors
   `KNOWN`), then extend the assertions in `preview/check.js` if the new skin
   introduces values worth pinning (a card border, an accent fill, a header
   offset).
5. **`mvn test` + `node preview/check.js`.**

## How switching works

- `js/theme.js` is the one module that knows a theme exists. It moves two
  things: `document.documentElement.dataset.theme` and the `href` of
  `#themeSkin`. It paints the chips from the backend reply.
- Clicking a chip in Settings applies **and** saves immediately — a theme is not
  form state, so there is no Save button and Escape leaves it in place. A failed
  save reverts the repaint and reports the backend's message under the chips.
- Persistence is `~/.van-gogh/theme.conf` (`theme = liquidneon`), read and
  written by `ThemeConfig` — same shape as `llm.conf`, atomic replace, no
  secrets. It is deliberately not `localStorage`: the config dir is already the
  single source of truth for the other preferences, and it survives a webview
  cache clear.
- On launch `js/app.js` awaits `initTheme()` before the first content load, so
  the first frame is the default sheet and the saved skin lands while the grid
  still shows skeletons.

## Traps

- **A theme change is two resets, not one.** The new sheet loads asynchronously
  and the controls transition their colours, so anything that reads computed
  styles right after a swap sees the *old* skin. `preview/check.js` waits for the
  new sheet to appear in `document.styleSheets` and then lets the 0.2 s fade
  finish. Tests that skip this are flaky, not wrong about the feature.
- **Header offsets are theme-owned, not base-owned.** The two skins place the
  Search button and the AI toggle differently; sharing those numbers makes the
  toggle overlap the button in Liquid Neon. The harness asserts the two boxes do
  not overlap under either skin rather than trusting arithmetic.
- **Breakpoints belong to the grid, not to the skin.** The column ladder lives
  once at the end of `base.css` — 5 columns, 4 at `1180px`, 3 at `900px`, 2 at
  `768px`: a theme decorates, it never reflows. A `@media (max-width: …)` rule
  that changes `.card-grid` inside a `theme-*.css` is the bug this replaced; the
  harness asserts the two skins step in lockstep so the drift cannot come back.
  (Theme media queries for `prefers-reduced-motion` are decoration, not layout —
  those stay per-skin.)
- **The `900px` step is tied to the sidebar.** `js/ui.js` collapses the 240px
  rail below `NARROW_WIDTH = 900`, so the grid drops to 3 columns on the same
  boundary and regains the room the rail gave up. Change either number and the
  other has to move with it, or the ladder steps in a visible seam.
- **Card meta is `nowrap` for tokens, wrapping for prose.** `.card-meta-item`
  stays whole (`2010`, `Science Fiction`, `★ 8.5`) so a label never breaks
  mid-word; the book card overrides it to `normal`, because its meta is the
  phrase `Author · N pages` and an author name has no length limit. At five
  columns that phrase is the one thing that cannot fit — it wraps, like the
  book title.
- **`cyberpunk.css` / `cyberpunk.html`** are the untracked prototype Liquid Neon
  was derived from. They are superseded (`?theme=liquidneon` in the harness is
  the way to look at that skin now) and should not be edited.
