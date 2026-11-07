# javafx-web (vendored reference)

Placeholder repository for vendored JavaFX Web (`javafx.web`) reference source.

## Purpose

Provide real implementation/tests for `javafx.web` (WebView, WebEngine, etc.) that agents can read
as reference. Do **not** import from this folder in application code — VanGogh imports JavaFX via
normal Maven dependencies (`org.openjfx:javafx-web`).

## How to populate

Follow the subtree workflow documented in the project `AGENTS.md`:

```sh
# from the van-gogh repo root
git subtree add --prefix=repos/javafx-web <repo-url> <branch> --squash
```

Replace `<repo-url>` with the source repository you want to vendor (e.g. an OpenJFX fork or a
JavaFX WebView example project) and `<branch>` with its default branch.

## Notes

- This folder is excluded from VS Code auto-import, file watcher, search, and explorer (see
  `.vscode/settings.json`).
- Treat contents as read-only reference material.
