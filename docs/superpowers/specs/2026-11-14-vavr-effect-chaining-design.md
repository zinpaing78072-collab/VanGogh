# Vavr effect chaining — design (2026-11-14)

## Intent

Chain every effect-full operation in the fetch pipeline with Vavr monads, with
full domain error types, and make error **accumulation** and
**short-circuiting** available in the same vocabulary. Explored in conversation
first; three shapes were laid out, Shape 3 was picked.

## Approaches

1. **Try everywhere** — mechanical `throws Exception` → `Try<T>`; no new types.
2. **Try for transport, `Either<String,_>` for outcomes** — light Either with
   string messages.
3. **Full domain error types + accumulation (picked).** Sealed `FetchError`
   hierarchy; `Either<FetchError, T>` spine; `Validation` accumulation nodes.

## Decisions (made during the slice)

- **Error model:** `vangogh.error.FetchError` — sealed interface, records
  `Transport` / `HttpStatus` / `Payload` / `BadInput`, one hierarchy shared by
  every content form. `FetchError.uiMessage` is the single exhaustive-switch
  renderer (new content forms become compile-time checklists).
- **Spine (short-circuit):** clients return `Try<ApiResponse>`; services return
  `Either<FetchError, List<…>>` via `toEither().mapLeft(...).flatMap(...)`.
  Controllers unwrap at the edge only (`fold` → throw), preserving Krema's
  error-reply contract and the legacy message format
  ("TMDB trending failed: HTTP 500 …" — asserted in tests).
- **Accumulation:** `Validation.combine(...).ap(...)` at independent-extraction
  nodes: games structure (games array + boxart map) and JCEF command args.
  **Verified against vendored vavr 1.0.1:** `For` over `Validation` preserves
  `L` (no `Seq` widening) — accumulation is `combine/ap`, not `For`; `Seq.of`
  does not exist (use `List.of`); `Option.or(Supplier)` does not exist
  (`orElse(Supplier)` does).
- **Posters:** `loadPosterDataUri` → `Option<String>` failover chain
  (lazy `Stream` of `Try`s, `takeUntil`, interrupt flag restored, winning
  source carried as `Tuple2` for the `rememberPosterSource` side effect).
  Cache-aside: `Option` chain with `orElse(Supplier)` + `peek` put-back.
- **Lenient parse kept:** row-level fields keep their defaults
  (`id=0`, `"Untitled"`, …) — tests assert this. Strictness is
  structure-level only: malformed JSON / missing nodes now surface as
  `Payload` errors instead of silently empty grids. `parseMovies`/`parseGames`
  stay as lenient facades over the strict spine.
- **Cosmetic effects stay best-effort:** poster-proxy preference file IO is
  `Try` with swallowed failure — never escalated to the error model.
- **Untouched:** FFM/launch plumbing (`MacMenubarPlugin`, `AppNativePreload`,
  `JcefVanGoghApp` resource extraction), `ConcurrentHashMap` poster caches,
  `GameController`'s blocking `future.get()` (Krema synchronous-reply rule),
  frontend (`app.js` unchanged).
- **Behavior changes (deliberate):** first game poster no longer fetches on
  the UI thread (`Future.traverse` moves all to the pool); payload-shape
  failures now produce a visible error instead of a blank grid.

## Parked

- UI notice for accumulated row errors (needs the `items.error` payload path
  + frontend change) — separate slice.
- `For`-comprehension showcase: `For` over `Validation` doesn't widen errors;
  revisit if a dependent-chain fan-in site appears.
