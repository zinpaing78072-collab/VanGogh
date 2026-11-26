# UI SDK adoption — design

Date: 2026-11-23
Status: draft for approval
Scope: `frontend/` (React page) and the agent-facing docs; no Java/bridge changes.

## Intent

Adopt [UI SDK](https://github.com/vercel-labs/ui) (`@ui-sdk/react` + `@ui-sdk/core`) as the behavior layer for VanGogh's interactive UI, so coding agents build and change controls from documented primitives and behaviors instead of hand-rolling ARIA, focus and keyboard handling. The look stays ours: UI SDK components are unstyled, and the existing role tokens, `base.css` and theme skins keep owning the visual design.

Why now: the frontend is already React 19 + TypeScript + Vite, the library targets exactly that stack, and its docs are written for agents (`llms.txt`, per-component pages, a skill). The current page hand-rolls roles (`role="slider"`, `role="group"`, `aria-pressed` chips) that the library ships correctly.

## What the library is (verified against `0.1.0-beta.13`)

- Two packages: `@ui-sdk/react` (components) and `@ui-sdk/core` (framework-free behavior; peer of react). Requires React ≥19 and `@types/react` ≥19.2.10 — we have 19.3.1 / 19.3.0.
- Per-entry imports (`@ui-sdk/react/toggle`, `/segmented-control`, `/slider`, `/input`, `/field`, `/toolbar`, `/carousel`, `/scroll-area`, …). No root import.
- Unstyled, no providers, no portals. Parts own roles, ids, ARIA and `data-slot` / state attributes. `render={<element />}` swaps the element; `className`, styles and refs combine.
- `defaultValue` + native `onChange` work alongside controlled `value` + `onValueChange(value, details)`.
- Optional `@ui-sdk/core/web.css` default look — **we will not import it**.
- The `ui-sdk` skill ships in `node_modules/@ui-sdk/core/skills/ui-sdk/` (88 KB incl. references); docs ship in `node_modules/@ui-sdk/react/docs/`.
- Beta: APIs may change between releases. Pin the exact version.

## Goals

1. Agents have one documented way to build an interactive control here: ui-sdk primitive → wrapper → page, with tests.
2. Every migrated surface keeps its look, its DOM contract with `preview/check.js`, and its session/store behavior.
3. The dependency is contained: a beta break is fixed in `frontend/src/ui/`, not across pages.
4. Onboarding is in the repo: AGENTS.md rules, a vendored skill, a `docs/ui-sdk.md` mapping/recipe page.

## Non-goals

- No visual redesign, no new features (no command palette, dialogs, toasts, drag-and-drop).
- No Java, bridge, IPC, store or session changes.
- No `@ui-sdk/core/web.css`; no Tailwind rewrite; no theme changes.
- No activation of inert chrome (Home, My Lists, Favorites, notifications, profile).
- No Windows/WebView2 verification (not available on this machine).

## Anti-goals

- Two sources of truth for state, or duplicate roles/ARIA on a part.
- A slice that changes behavior, visuals and tests all at once without a real-app check.
- Silently breaking a `preview/check.js` selector or a session invariant.
- The API key entering React state.
- Doubling the committed page bundle.

## Constraints (from the repo)

- Page is built by Vite into the committed `src/main/resources/movie-page/`; no Node at runtime. `npm run check:dist` byte-compares a fresh build; commit source and output together.
- `npm run verify` = typecheck, lint, Vitest, build, headless preview check. `preview/check.js` drives Chromium over CDP and asserts ids/classes, layout and behavior.
- Two deliberately uncontrolled inputs: `#searchInput` (Header) and the Settings fields. The harness assigns `.value` and clicks in one task, and the API key never enters page state. A controlled field would hand the submit handler the previous render's value.
- CSS cascade: structure and role tokens first, theme sheet last (`#themeSkin`); the responsive grid ladder lives only at the end of `base.css`; the 2:3 similar-rail fix must survive.
- Sessions survive navigation (one per form in `app/store.ts`); `bridge/` is the only place IPC names/payloads appear; cards take `onOpen`.
- Runtime is Krema's system webview (WebKit on macOS); the preview is Chromium. Focus/scroll behavior must be smoked in the real app for slices that change it.

## Approaches

The conversation settled the scope as **C — component-layer migration**, one surface per slice. The alternatives:

- **A. Foundation only** (deps + skill + AGENTS.md note, no UI change): cheapest, but nothing proves agents build well with it and the presentation sees nothing.
- **B. Foundation + one proof slice**: proves the loop, but leaves the rest hand-rolled and the mapping undocumented.
- **C. Foundation + a documented migration of every interactive surface, one slice each** (chosen): establishes the pattern, replaces hand-rolled ARIA where it matters, and keeps each PR reviewable.

Decisions inside C (see "Decisions" for the full list):

- **Wrappers, not direct imports.** Pages import from `frontend/src/ui/`; only that folder imports `@ui-sdk/*`. Trade-off: one indirection; benefit: a beta break is one-file fixes and the mapping is documented.
- **Vendor the skill** into `.opencode/skills/ui-sdk/` with a source/version note. Trade-off: a copy to refresh on upgrade; benefit: every teammate and fresh clone gets it without running `npx skills`.
- **Sidebar: a custom roving-focus nav primitive** from `@ui-sdk/core`. `Toolbar` is semantically a toolbar, `Navigation Menu` is for panels; the nav is a list with one tab stop and arrow keys.
- **Rails: `ScrollArea`**, not `Carousel`. `ScrollArea` keeps native wheel/trackpad and adds styled scrollbars; `Carousel` would add paging buttons and DOM, a visual change we did not ask for. Revisit if the presentation wants paging.
- **Order: risk-first**, not presentation-first: settings → search → sidebar → player → movie → book → game → grid. Settings is the smallest self-contained surface and proves the loop; the grid is last because its roving focus touches every card.

## Architecture

```
frontend/src/ui/            wrapper layer — the only importer of @ui-sdk/*
  README.md                 conventions (one screen)
  <component>.tsx           one wrapper per adopted primitive
frontend/src/components/    pages use wrappers, keep their classes/ids
docs/ui-sdk.md              mapping table, recipes, version policy, selector contract
.opencode/skills/ui-sdk/    vendored skill + SOURCE.md + LICENSE
AGENTS.md                   "UI SDK" subsection with the rules and doc paths
```

A wrapper:

- takes the same props the page already uses (`id`, `className`, `data-*`, handlers);
- imports from the specific entry, never the root or `@ui-sdk/core/internal/*`;
- passes our existing classes to the parts, so `base.css` / theme sheets keep working;
- owns the store bridge for controlled state and the ref bridge for uncontrolled fields;
- exposes no ui-sdk types beyond what the page needs.

Version policy: `"@ui-sdk/react": "0.1.0-beta.13"` and `"@ui-sdk/core": "0.1.0-beta.13"` (exact, no caret). Upgrading is its own slice with `npm run verify`, the preview check and a real-app smoke run.

## Slices

Every slice: one branch/PR, `npm run verify` green, preview check green, committed `movie-page` rebuilt, and a real-app (Krema/WebKit) smoke for focus/scroll changes. A slice keeps every `preview/check.js` selector it can; where a part's managed ARIA makes an old assertion impossible, the slice updates `check.js` in the same commit and says so in the PR.

### 0. Foundation

- Add the two pinned deps; commit the lockfile; `npm install`.
- Create `frontend/src/ui/` with `README.md` (wrapper rules, import rule, styling rule, test recipe).
- Vendor the skill into `.opencode/skills/ui-sdk/` (SKILL.md + references + `SOURCE.md`: package, version, refresh instruction) and its Apache-2.0 license.
- Add `docs/ui-sdk.md`: mapping table (surface → component), selector contract, test recipe, version policy.
- Add the "UI SDK" subsection to AGENTS.md: when to load the skill, which docs to read, import rules, "don't hand-roll roles we ship", no `web.css`, pinned version, wrapper rule, test rule.
- Add one smoke test that imports a component through the wrapper folder and renders it (proves Vite/TS/Vitest resolve the entries). No page markup changes.
- Record bundle sizes before/after in the PR.

Done: `npm run verify` and `npm run check:dist` pass; the page output is unchanged except for the bundle hash/size.

### 1. Settings (smallest self-contained surface)

- Theme chips → `SegmentedControl` (single choice, one tab stop, arrow keys). Keep `#themeChips`, `.theme-chip`, `data-theme-id`; replace `aria-pressed` assertions with `aria-checked` / `data-checked`.
- Provider chips → `SegmentedControl`. Keep `.provider-chip`, `data-provider`; update the `aria-pressed` assertion the same way.
- Reveal key → `Toggle` (`#settingsReveal`, `disabled`, `aria-pressed` stays).
- Timeout / titles → `NumberInput`; base URL / model → `Field` + `Input`; Save → `Button` (`#settingsSave`, disabled/pending).
- Key stays uncontrolled and out of React state. Use `Input` with `defaultValue` + ref if the part forwards them; otherwise keep the native `<input>` inside `Field` — the secret rule wins over purity.
- `#settingsApiKey` keeps `type`, `value`, `placeholder`; `#settingsBaseUrl` etc. keep their values.

Tests: arrow keys move the theme/provider choice; Enter/Space selects; reveal toggles; save disabled state; the key never appears in page state.

### 2. Header search

- `Field` + `Input` + `InputGroup` + `InputClear`; `#searchInput` stays uncontrolled with `defaultValue`; Enter submits; `#searchBtn` → `Button`; `#aiToggle` → `Toggle` (keeps `aria-pressed`, survives form switches).
- Clear appears with text and returns focus. The harness sets `.value` without an `input` event, so the clear's visibility is not asserted; if we assert it, dispatch `input` first.
- Search semantics unchanged: empty term → trending; AI on → recommend.

Tests: typing + Enter calls the loader; clear returns focus and empties; AI toggle persists across a form switch.

### 3. Sidebar

- Custom `nav-list` primitive from `@ui-sdk/core` roving focus: one tab stop, arrows, Home/End, `aria-current="page"` on the active form, `aria-disabled` inert items. Keep `#sidebarNav`, `.nav-item`, `[data-domain]`, `.nav-label`, `.active`, `#settingsNav`.
- Collapse toggle → `Button` with `aria-expanded` / `aria-controls` (`#sidebarToggle`). Narrow auto-collapse (`NARROW_WIDTH = 900`) unchanged.
- Inert items stay discoverable but do nothing (current behavior).

Tests: roving focus order; click still selects; inert item is a no-op; the existing sidebar block in `check.js` passes unchanged.

### 4. Song player + song detail

- Seek → `Slider` (Control/Track/Indicator/Thumb). This removes the hand-rolled `role="slider"` div; arrows/Home/End come from the part. Keep `#playerTrack`, `#playerFill`, `#playerDuration`, `#playerBar`.
- Play/pause and restart → `Button` with changing labels (not a `Toggle`: it is an action, not a pressed state).
- Detail disclosures (credits/about) → `Toggle` / `Button` as their semantics require; keep `.credits-toggle`, `data-action` hooks.

Tests: keyboard seek updates the fill and elapsed time; play/pause label flips; player block in `check.js` unchanged plus a keyboard-seek assertion.

### 5. Movie detail

- Watchlist / Favorite → `Toggle` (keeps `aria-pressed` and `data-action`); trailer → `Button`.
- Cast / stills / similar rails → `ScrollArea`; keep `.movie-sim-card`, `.movie-sim-poster` and the 2:3 fix; `preview/check.js` still asserts the shape.
- Escape/Back unchanged.

### 6. Book detail

- Same rail/action pattern; keep `.book-sim-*`, `#bookView` hooks and the author/edition slots.

### 7. Game detail

- Same pattern; keep `.game-rel-*`, `#gameView` hooks and the hero/stat band.

### 8. Browse grid (optional, last)

- Roving focus over cards (arrows, Home/End, Enter/Space opens); card components keep their markup and `onOpen`. Only the grid wrapper and focus styles change. Skip if the presentation deadline tightens.

## Behavior and styling rules

- Parts own roles, ids, ARIA and `data-slot`; delete our duplicate attributes when a part manages them. Never pass conflicting roles to a part.
- Style state from `data-*` (`data-checked`, `data-pressed`, `data-open`, `data-disabled`) and the component's CSS variables; no inline styles for state.
- No `!important`; no new cascade layer (we do not import `web.css`); the theme sheet stays last; the grid ladder stays base-only.
- Focus rings use `--focus`; text 4.5:1, control edges/focus 3:1.
- Controlled where the store owns the value (active domain, theme, toggles); uncontrolled only for the search field and the Settings fields (harness + secret).
- One Escape owner per surface. The global `useEscape` stays until a Dialog/Popover owns dismissal.
- Key lists by id, never index.

## Testing

- **Vitest + Testing Library** (jsdom): roles, ARIA, keyboard, focus return, controlled/uncontrolled. Every slice adds at least one test that would fail on the old markup.
- **`preview/check.js`** (real Chromium): selector contract, layout (rail shapes, grid columns), keyboard via CDP, and the scenario pages. Extend it per slice; keep existing blocks green.
- **Real app**: `mvn compile exec:exec` smoke for slices 3–8 (WebKit focus/scroll). The JCEF shell is an optional Chromium cross-check; its native-menu freeze is known and unrelated.
- **Gates**: `npm run verify`, `npm run check:dist`, `mvn -q clean compile` before the final merge.

## Bundle and performance

Current committed page: 337,452 B JS + 68,748 B CSS. Per-entry imports keep the addition tree-shaken. Budget: +10% JS (≈ +34 KB) for slices 0–2, +20% (≈ +67 KB) ceiling overall; measure uncompressed and gzip per slice and record it in the PR. A slice over budget stops and reports before merging.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Beta API churn | Exact pin; all imports behind `src/ui/`; upgrade is its own slice |
| WebKit vs Chromium differences | Real-app smoke for focus/scroll slices; preview remains the fast loop |
| ARIA/selector regressions | Selector contract + `check.js` updates in the same commit; role-based tests |
| Uncontrolled harness breaks | `defaultValue` + refs; fall back to native input inside `Field` where needed |
| Bundle growth | Per-entry imports; measure per slice; budget above |
| Skill drift | Vendored copy with `SOURCE.md`; refresh on upgrade |
| Theme cascade breakage | No `web.css`, no `!important`, state styling via data attributes |
| Ref/render contract gaps | Verify in slice 1; native fallback documented |

## Rollback

Each slice is a revertible branch. Slice 0 is inert (no page markup change), so it can land first and be reverted without UI impact. Once later slices land, reverting a single slice restores that surface's hand-rolled markup without touching the others.

## Decisions (agent's calls, check these)

1. Pin `0.1.0-beta.13` exactly, no caret.
2. Wrapper layer `frontend/src/ui/`; pages never import `@ui-sdk/*`.
3. Vendor the skill into `.opencode/skills/ui-sdk/` with `SOURCE.md` + license, rather than `npx skills` per machine.
4. No `@ui-sdk/core/web.css`; styles stay in the existing sheets.
5. Order: settings → search → sidebar → player → movie → book → game → grid.
6. Sidebar uses a custom roving-focus nav primitive, not `Toolbar` or `Navigation Menu`.
7. Rails use `ScrollArea`, not `Carousel`.
8. Play/pause stays a `Button` with a changing label, not a `Toggle`.
9. Keep every preview id/class possible; update `check.js` only where managed ARIA makes an assertion impossible (theme/provider chips: `aria-pressed` → `aria-checked`).
10. Budget: +10% JS for slices 0–2, +20% ceiling overall.
11. The Settings key field may remain a native `<input>` inside `Field` if the part's ref/`defaultValue` contract does not survive; the key never enters React state.

## Left to the builder

- Exact wrapper APIs and prop names per component; which parts take `className`/`id`/`ref` and which need `render`.
- The concrete CSS selectors for `data-*` states per surface, and focus-ring styling.
- `NumberInput` formatting/locale and `Slider` value formatting in the player.
- Whether `ScrollArea`'s DOM fits each rail's CSS (padding, snap, 2:3 poster box) without wrapper churn.
- The roving-focus primitive's API for the sidebar and, later, the grid.
- The refresh command/content for the vendored skill on upgrade.
- The exact `check.js` diff per slice.
