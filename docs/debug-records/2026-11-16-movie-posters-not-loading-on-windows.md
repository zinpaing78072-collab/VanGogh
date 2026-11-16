---
title: "Debug Record — Movie posters never load on Windows (events dropped off the UI thread)"
date: 2026-11-16
project: "[[VanGogh]]"
tags: [debug-record, krema, windows, posters, threading]
status: fixed
---

# Debug Record — Movie posters never load on Windows (events dropped off the UI thread)

## Symptom

On Windows (Krema shell, `mvn compile exec:exec`), searching or trending
renders movie cards with titles, years, ratings — but every poster stays on
its placeholder forever. No error, no retry, nothing in the console. The Game
filter works fine; its boxart loads normally.

## Environment

- Windows 11, Krema shell (`VanGoghApp`, krema-core 0.3.2)
- Repro 100%: any movie search or trending load
- JCEF shell unaffected (its emitter marshals `executeJavaScript` onto the
  Swing EDT, so background-thread emission still reaches the page)

## Root cause

Already recorded in AGENTS.md as a known krema-core 0.3.2 Windows gap, but
only the games slice honored it:

> Windows constraint: `engine.eval` is silently dropped when called off the
> UI thread, so async command replies and backend events never reach the page.

The movie slice pushed `events.emit("poster", ...)` directly from the IO pool
inside a vavr `Future.traverse` fan-out. On macOS and in JCEF that works. On
Windows the emit happens on a pool thread → `engine.eval` is dropped
silently → the `"poster"` events are emitted into the void. No exception is
thrown anywhere, so the page just waits forever. AGENTS.md even predicted
this: "if a Windows build ever matters, the movie slice needs a drain like
the games slice." It started to matter.

## Why games worked

`GameController` never emits from a background thread. `fetchAndOffer`
queues finished posters into a `ConcurrentLinkedQueue`, and the page drains
them by polling `pollGamePosters` — a `@KremaCommand`, so the emit happens on
the UI thread inside the command handler. The drain's return value doubles as
the completion signal (`-1` when nothing is pending).

## Fix

Mirror the games pattern in `MovieController`:

- `requestMovies` no longer emits; it computes `posterable`, sets
  `posterTotal` synchronously (before the command returns, so the first poll
  can't see a stale count), and starts `Future.traverse` prefetches that
  `fetchAndOffer` completed posters into `posterQueue` (skips counted via
  `posterSkipped` so failures can't stall the drain).
- New `@KremaCommand pollMoviePosters()` drains the queue, emitting
  `"poster"` events on the UI thread, and returns the outstanding count or
  `-1` when done — same contract as `pollGamePosters`.
- `app.js` polls the drain after rendering either content form
  (`pollMoviePosters` / `pollGamePosters`); the payload and `on("poster", …)`
  listener are unchanged.
- `JcefBridge` command whitelist gains the `pollMoviePosters` case (it
  matches Krema's reflection-based `@KremaCommand` dispatch).

## Verification

- `mvn clean test` green: 67/67, including the two retargeted
  `MovieControllerTest` drain tests (drain emits on UI thread; skipped
  posters still reach `-1`).
- Manual on Windows: trending + search now fill posters under the Krema
  shell; generation counting still discards stale posters when a new search
  supersedes an in-flight prefetch.

## Lessons / conventions

- In this codebase, **no backend event may be emitted from an IO-pool
  thread** while the Krema 0.3.2 Windows gap stands — even if the feature
  "works" on macOS, it is silently broken on Windows. New content forms
  (Music, Book) must ship with a drain command + poll loop from day one.
- Keep the drain contract identical across controllers: queue finished
  work → UI-thread `@KremaCommand` drain emits and counts → returns
  outstanding or `-1` as the completion signal.
- Don't rely on errors to notice this class of bug: a dropped `engine.eval`
  throws nothing. If posters "don't load" with zero errors on one OS but not
  another, check which thread the emitter runs on before touching the API
  layer.
- When the project can move to a Krema release whose eval is thread-safe on
  Windows, the drain dance can be dropped for both slices.
