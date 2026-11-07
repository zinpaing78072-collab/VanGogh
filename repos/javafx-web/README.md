# javafx-web (vendored reference)

Reference repository for JavaFX Web (`javafx.web`): a minimal, runnable example of `WebView` /
`WebEngine` usage, including JavaScript <-> Java interop.

## Contents

- `src/main/java/vangoogh/webview/WebViewDemoApp.java` — JavaFX app demonstrating load state,
  exposing a Java object to JS via `JSObject`, and calling JS from Java.
- `src/main/resources/web/index.html` + `app.js` — page loaded by the WebEngine.

## Purpose

Provide real, readable implementation that coding agents can learn from when working with
JavaFX Web in the VanGogh project. Read-only reference material — do not import from this
folder in application code; VanGogh imports JavaFX via normal Maven dependencies.

## Build / run (standalone)

```sh
mvn -q javafx:run
```

## Syncing into VanGogh

This repo is vendored into VanGogh under `repos/javafx-web` via `git subtree`:

```sh
# from the VanGogh repo root
git subtree pull --prefix=repos/javafx-web https://github.com/zinpaing78072-collab/javafx-web.git main --squash
```
