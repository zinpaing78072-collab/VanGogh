---
title: "Theme switching — Starry Night + Liquid Neon, built to take more"
date: 2026-11-19
project: "[[VanGogh]]"
tags: [frontend, css, settings, design, theme]
status: draft
---

# Theme switching — Starry Night + Liquid Neon

## Intent

The app ships two skins today, but only one is real: `style.css` (Starry Night)
is loaded by `index.html`, while `cyberpunk.css` is a hand-copied fork that only
runs through the standalone `cyberpunk.html` prototype. A user cannot pick a
theme, and the app does not remember one.

This slice turns the skins into a first-class, persisted choice on the Settings
page — **Starry Night** (default) and **Liquid Neon** (the existing neon skin,
renamed) — and restructures the CSS so the next theme is one file plus one
registry entry, not another 700-line fork.

Success looks like: Settings has an Appearance card with a theme chip per skin;
clicking one repaints the whole app immediately (shell, cards, song detail,
Settings itself); the choice survives a restart; and adding theme #3 touches one
CSS file and one entry in a Java list.

## Goals

- A theme picker on the Settings page, applied live, persisted, and restored on
  launch.
- Theme = CSS only. No theme-specific branches in page JS, no per-theme HTML,
  no duplicated component markup, **and no palette values in JS or Java**.
- One shared structural stylesheet plus thin per-theme files; the Starry Night
  and Liquid Neon visual rules stop being two copies of the same 700 lines.
- Liquid Neon gains the header AI toggle styling and the `.veil::after` glow it
  never had, with real header metrics rather than the Starry offsets.
- The song detail view and the Settings page follow the active theme instead of
  staying gold.
- The preview harness covers both skins and the switch; `mvn test` covers the
  persistence.

## Non-goals

- No theme follows the OS light/dark preference, no auto theme, no scheduled
  switching.
- No new theme designs beyond the existing neon skin. `liquidmetal.jpeg` (a
  liquid-chrome backdrop, currently unreferenced and untracked) stays unused —
  it is available for a later theme.
- **No layout changes for Starry Night.** See "Slice 1 stays pixel-identical"
  below; the drift between the two skins' responsive rules is deliberately left
  in place.
- No theme sync across machines, no user accounts, no cloud storage.
- No change to the `window.krema` command/event contract beyond two new
  synchronous commands.
- No changes to the browse grid, card shapes, or IPC cadence.

## Anti-goals

- `localStorage` as the source of truth. The app already keeps small
  preferences under `~/.van-gogh/`, and Krema's system webview gives no
  documented guarantee that Web Storage survives a relaunch — a theme that
  resets itself every launch would be worse than no picker.
- Per-theme HTML pages (the `cyberpunk.html` pattern): the markup drifts and
  every feature has to be added twice.
- Per-theme copies of `music-detail.css` / `settings.css`. Those two carry the
  Starry palette as literal hex/rgba values; copying them per theme multiplies
  the maintenance the same way the skins did.
- A second theme registry in JS. Two lists means a chip that always fails to
  save, or a saved theme with no label.
- Palette literals in the picker (swatch colours). A third copy of every
  theme's colours, and the end of "theme = CSS only".
- Theme logic inside `render.js`, `content.js`, or the card builders.
- A native `<select>` for the theme picker (macOS NSMenu popup traps the AppKit
  thread and freezes the window).

## Constraints

- Both shells must work: Krema (system webview) and JCEF (bundled Chromium).
- Windows Krema constraint: every command that replies to the page must be
  synchronous on the UI thread. `loadTheme` / `saveTheme` are one small file
  each, so they stay synchronous like `loadLlmSettings` / `saveLlmSettings` —
  no kickoff/drain pair.
- `maven-compiler-plugin` already binds command args by name.
- Adding a command means two registrations: `VanGoghApp.commands(...)` and the
  `JcefBridge` `switch`.
- `repos/` is read-only.
- `cyberpunk.css`, `cyberpunk.png`, `cyberpunk.html` and `liquidmetal.jpeg` are
  untracked, so the rename lands as an add (786 lines + a 2.6 MB PNG), not a
  `git mv`. `liquidmetal.jpeg` is not committed in this slice.
- Presentation build rules from `AGENTS.md` still apply: one feature slice per
  PR, build green, feature demonstrably working.

## Approaches

1. **Swap whole stylesheets.** Keep `style.css` and the neon file intact and
   re-point one `<link>`. Smallest possible diff and exact fidelity, but every
   new theme forks four files, and the two existing files have already drifted
   in both directions. The trap the user chose to avoid.
2. **Shared base + thin theme files (chosen).** Extract structure and geometry
   into `base.css`; base declares the role tokens with their Starry values as
   the default, each theme file overrides the roles it changes and adds its own
   decorative rules. The song detail and Settings stylesheets stop hard-coding
   colours and read the same role tokens, so they theme for free.
3. **Token-only theming.** One stylesheet, themes redeclare `:root` colours
   only. Cheapest forever, but Liquid Neon needs mono display type, HUD corner
   brackets, uppercase headings, 4px radii and no gradients — none of which are
   colours. Adopting it would mean redesigning the neon skin, which the user
   explicitly does not want.

Chosen: **2**, sequenced so the CSS split lands as its own low-risk slice
before the switching feature is built on top of it.

## What the build looks like

### The token contract (answers "where do the palette values live")

Base owns the **role tokens**, and every stylesheet in the app references roles
only:

```
--bg --panel --panel-hover --panel-inset --panel-deep
--accent --accent-hover --accent-ink --accent-line --accent-fill
--text --text-soft --text-muted --rating --danger
--border --border-strong --blur-glass --radius
```

- `base.css` declares them once in `:root` with Starry Night's values, so a
  missing or broken theme link still renders a sane page. This is the documented
  reason base is not colour-free.
- A theme file overrides only the roles it needs plus the rules whose *shape*
  differs (Liquid Neon overrides nearly all of them; Starry Night overrides
  almost none).
- `--star-*` / `--neon-*` / `--night-*` names **stop existing**. They are not a
  public contract: they are used 50× in `style.css` and ~30× in
  `music-detail.css` / `settings.css`, and `var(--undefined)` in `border-color`
  does not fall back — it invalidates the declaration at computed-value time, so
  a leftover star name under Liquid Neon renders visibly wrong instead of
  drifting slightly. Renaming them to roles is the one mechanical pass that
  makes theming safe.
- The `@supports not (backdrop-filter…)` fallback block moves out of `base.css`
  and into each theme file, because it overrides `--panel` / `--panel-inset` and
  those values are theme-owned.

### CSS layers

| File | Role |
|---|---|
| `base.css` | Reset, layout, geometry, component box model, skeletons, focus rings, reduced motion, **and the role-token defaults**. Structure only otherwise: no decorative values that a theme would want to change. |
| `theme-starry.css` | Starry Night: `starry.png` backdrop, the frosted-veil treatment, the `@supports` glass fallback, and the handful of rules that differ from base. Expected to stay small. |
| `theme-liquidneon.css` | Liquid Neon: `liquidneon.png` backdrop, scanline veil, mono type, HUD brackets, uppercase headings, sharp radii, flat fills. The current `cyberpunk.css` content, renamed, re-scoped to roles, plus the two gaps below. |
| `music-detail.css` | Structure only. Its literal Starry values become role references. |
| `settings.css` | Same treatment as `music-detail.css`. |

`index.html` loads `base.css`, `music-detail.css`, `settings.css`, then the theme
link last so theme rules win without `!important`:

```html
<link rel="stylesheet" href="base.css">
<link rel="stylesheet" href="music-detail.css">
<link rel="stylesheet" href="settings.css">
<link rel="stylesheet" id="themeSkin" href="theme-starry.css">
```

`<html data-theme="starry">` is the single readable marker of the active theme
(used by JS and tests). The stylesheet swap is what repaints; `data-theme` is
not a selector prefix, so theme files stay plain override sheets.

### Rename, gaps, and what base does *not* absorb

- `cyberpunk.css` → `theme-liquidneon.css`; `cyberpunk.png` → `liquidneon.png`.
- Add to Liquid Neon: `.ai-toggle` (base, `:hover`, `[aria-pressed="true"]`,
  its `:hover`) and the `.veil::after` glow.
- The header offsets are **theme-owned, not base-owned**. The skins disagree on
  exactly the geometry that places the AI toggle:

  | | Starry Night | Liquid Neon |
  |---|---|---|
  | `.search-input` padding-right | 148px | 122px |
  | `.search-btn` right | 10px | 6px |
  | `.ai-toggle` right | 104px | *no rule* |

  Base owns the header bar and the flex row; each theme file places the button
  and the toggle. Liquid Neon needs real metrics, not a copy of Starry's: its
  button is ~110px wide starting at `right: 6px`, so the toggle sits around
  `right: 128px` and `.search-input`'s padding grows to ~174px. The harness
  asserts the two boxes do not overlap under either theme (see below) rather
  than trusting the numbers.
- Delete the dead `.card-genres` / `.card-genre` rules: no file under `js/`
  emits that markup (`cards.js` and the domain rows emit `card-meta` /
  `card-meta-item` only).

### Slice 1 stays pixel-identical

The builder check found the drift runs both ways, and moving it into base is a
*visual change* for Starry Night: adding Liquid Neon's `max-width: 1180px` /
`900px` rules turns Starry's 5-column grid into 4 columns (then 3) at those
widths, and `.card-meta-item { white-space: nowrap }` changes Starry's card meta
wrapping.

So slice 1 moves only what is already true for both skins. Liquid Neon's
breakpoints stay in `theme-liquidneon.css`; Starry's single 768px breakpoint
stays where it is; `.card-meta-item` spacing stays theme-owned because the two
disagree. Reconciling the breakpoints is a follow-up with its own before/after,
not a smuggled change inside a refactor PR.

### Settings UI

A new first card in the Settings view, above "AI recommendations":

```
Appearance
  Theme
  [ Starry Night ] [ Liquid Neon ]        ← chips, aria-pressed on the active one
  Your choice is saved immediately and applies to every view.
```

- Markup: one `.settings-card` with an empty `.theme-chips` group; the chips are
  rendered from the backend reply. No theme ids, labels or colours in JS.
- Interaction: a chip click applies at once (`data-theme` + stylesheet href) and
  then saves. No Save-button involvement; the AI card's Save/Cancel keep their
  current meaning, and Cancel/Escape leaves the new theme in place (immediate
  appearance changes are expected, not pending form state).
- Chip styling reuses the existing `.provider-chip` pattern. **Name-only, no
  swatch:** a swatch would need each theme's colours in JS, which is the
  duplicate-palette anti-goal; clicking a chip already previews the theme
  instantly, so the swatch buys nothing.
- On save failure the page reverts to the previous theme and shows the error in
  the existing `settingsStatus` line; nothing is written.

### Persistence (single theme registry)

- `vangogh.theme.ThemeConfig` — a small record with `read(Path)` / `save(Path)`,
  mirroring `LlmConfig`'s reader (key=value, `#`/`;` comments, BOM tolerance,
  atomic temp-file + move). No owner-only permissions; this is not a secret.
- **Known themes live only here**, as `(id, label)` pairs:
  `starry` → "Starry Night", `liquidneon` → "Liquid Neon". An unknown or
  missing id reads as `starry`; a save with an unknown id is rejected as
  `BadInput` rather than silently defaulting.
- File: `~/.van-gogh/theme.conf`, containing `theme = liquidneon`.
- `vangogh.controller.ThemeController` — `loadTheme()` / `saveTheme(String
  theme)`, both `@KremaCommand`, both synchronous. Reply shape:
  `{ status, error?, theme, themes }` where `themes` is the known `(id, label)`
  list, so the page cannot invent an id or an unsaveable chip.
- CSS file naming is a convention: `theme-<id>.css`. That is why no JS registry
  is needed to map an id to a stylesheet.
- Registered in `VanGoghApp.commands(...)` and the `JcefBridge` switch.

### Startup and flash

`js/app.js` awaits `initTheme()` before the first `loadContent(...)`:

1. `invoke("loadTheme")` → apply the stored theme (marker + href).
2. Then seed the first load. The Settings chips are painted from the reply when
   Settings opens.

`<html data-theme="starry">` and the `theme-starry.css` link in the HTML mean the
first paint is always a valid theme; a saved Liquid Neon switches a few
milliseconds later, while the grid is still showing skeleton cards. No
`localStorage` paint hint and no blocking of first paint — if the flash proves
noticeable in the real app, the follow-up is to hide `.sky`/`.veil` until the
reply lands, not to duplicate the source of truth.

### Preview harness and tests

**Slice 1 (the CSS split) proves itself mechanically, not by eyeball:**

- `preview/mock-krema.js` gets a minimal mock `loadTheme` and a `?theme=<id>`
  startup knob (mock-only, same size as the existing `?scenario=` handling).
- `preview/check.js` gains computed-style assertions pinning each skin's key
  values, run for both `?theme=` values: card background/border and hover
  border, accent button fill, header panel colour and blur, error text colour,
  and `#aiToggle` ↔ `#searchBtn` non-overlap via `getBoundingClientRect`. No
  screenshot baselines exist in this repo and adding one is out of scope;
  computed values catch a dropped shadow token or a lost `@supports` fallback.

**Slice 2 (the switch) adds behavior assertions:**

- the Appearance card renders one chip per known theme; Starry Night is pressed
  by default;
- clicking Liquid Neon sets `html[data-theme]`, swaps the theme link href, and
  makes exactly one save call;
- reopening Settings shows the saved theme pressed;
- a stored unknown id falls back to Starry Night;
- a save failure reverts the repaint and shows the error.

**Java tests:**

- `ThemeConfigTest`: missing file → `starry`; stored `liquidneon` round-trips;
  unknown id → `starry` on read, rejected on save; BOM/comment tolerance; no
  leftover temp file.
- `ThemeControllerTest`: reply shape, theme list contents and labels, error path
  for an unwritable location (mirrors `SettingsControllerTest`).

### Docs

- `docs/themes.md` — how the layers fit and the exact recipe for theme #3:
  one theme file named `theme-<id>.css`, one `(id, label)` row in
  `ThemeConfig.KNOWN`, one harness assertion set. No JS edit, no Java reply
  change.
- `docs/preview-harness.md` — the `?theme=` knob and the new assertions.
- `AGENTS.md` — Settings description (Appearance card), the frontend module
  list (`js/theme.js`), and the theme section pointer.

## Decisions

- **Role tokens in base, defaults = Starry Night.** A theme file overrides roles
  and shape rules; `--star-*` names are deleted app-wide. Base holding a default
  is what keeps a broken theme link from rendering an unstyled page, and it is
  the only way `music-detail.css` / `settings.css` theme without per-theme
  copies.
- **`data-theme` is a marker, not a selector prefix.** Keeps theme files as
  plain override sheets and makes the active theme observable to tests.
- **Java is the only theme registry; chips are name-only.** One list, one label
  per theme, no palette values outside CSS, and no chip that cannot save.
- **Theme is CSS only.** JS moves two attributes (`data-theme`, the link href)
  and nothing else.
- **Immediate apply, immediate save.** A theme is not form state; the picker
  behaves like the AI toggle, not like the AI form.
- **Config file over Web Storage.** Matches `llm.conf` / `poster-proxy.conf`,
  is testable without a webview, and survives a clear-cache.
- **Separate `ThemeController`.** `SettingsController` is shaped around
  `LlmSettingsReply`; theme has its own validation and its own reason to change.
- **Slice 1 is pixel-identical for both skins.** The two-way drift is named and
  deliberately deferred; a refactor PR that also changes Starry's grid at
  1000–1180px cannot be reviewed as a refactor.
- **Header offsets are theme-owned.** They are part of each skin's header
  composition, and sharing them produces a real overlap once Liquid Neon gains
  an AI toggle.
- **Two slices, two PRs.** CSS split first (harness-proven), then the switching
  feature.

## Open questions

- **Delete `cyberpunk.html`?** The standalone prototype duplicates 322 lines of
  markup that will drift from `index.html` the moment base splits. My
  recommendation is to delete it and let `?theme=liquidneon` in the harness
  replace it, but the file is the user's untracked work, so it is their call.
- **Should Starry Night get Liquid Neon's responsive breakpoints later?** A
  4-column grid at 1000–1180px may well be better, but it is a visual change and
  does not belong in this design. Logged as a follow-up, not built here.

## What's left to the builder

- The exact split line between `base.css` and the theme files, rule by rule. The
  inventory is ~30 identical rules, ~48 differing ones, 7 Starry-only and 16
  neon-only.
- Which of the song-detail `rgba` shades collapse into `--accent-line` /
  `--accent-fill` / `--ink-deep` versus staying literal.
- Liquid Neon's AI toggle metrics (starting point above) — verified by the
  non-overlap assertion rather than by trusting arithmetic.
- Whether a theme change should animate at all; if it does, it must respect
  `prefers-reduced-motion` and must not delay the repaint.
