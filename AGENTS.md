# AGENTS.md — VanGogh

Project rules for VanGogh — entertainment content recommendation desktop app (Java 25 + Krema 0.3.2 system webview).

## Project management (Obsidian vault)

- The team's shared project-management vault is synced via Google Drive. On any machine, locate it by searching for a folder named `team-obsidian-vault` (look under the Google Drive sync folder first).
- Key files inside the vault (paths relative to vault root):
  - `10 Projects/VanGogh/description.md` — this project's description
  - `00 Meta/conventions.md` — naming, linking (`[[...]]`), YAML frontmatter, tasks, Google Drive sync rules
- Follow the vault conventions for note naming, linking, and YAML properties.
- One person at a time per note (Google Drive does not merge Markdown). Wait for sync before switching machines.

## Git conventions

- Remote: `https://github.com/zinpaing78072-collab/VanGogh.git` (origin)
- Default branch: `main` (protected — never commit directly to `main`)
- Sync with main before every coding sessions
- Workflow: create a feature/fix branch (`feat/<short-name>`, `fix/<short-name>`), commit there, open a PR, merge after review. Keep `main` green.
- Commit messages: short, imperative, lowercase first word (e.g. `add .pen file`, `Fix JavaFX runtime missing error and add run wrapper`). Use conventional-ish style; no strict prefix required.
- Build artifacts (`target/`, `.class`, `.log`, `.DS_Store`) are gitignored — never commit them.
- Run `mvn -q clean compile` (or `mvn test`) before committing; keep the build green.
- Push branches to `origin`; merge into `main` via PR only. Do not rewrite published history on `main` or any shared branch.

## Build / run

- Java 25 (LTS), Krema 0.3.2 (system webview via Panama FFM). Compiling requires
  a JDK 25+ install (pom compiles with `--release 25`); runtime is macOS default
  JAVA_HOME.
- `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home mvn compile exec:exec`
  to run the app. `exec:exec` forks `${java.home}/bin/java` so the pom's JVM flags
  (`-XstartOnFirstThread`, `--enable-native-access=ALL-UNNAMED`) apply.
- Do NOT use `mvn exec:java` — it reuses Maven's JVM and drops the pom flags, so
  the Krema window never appears on macOS (AppKit requires the event loop on the
  first thread).
- macOS launch rule: `-XstartOnFirstThread` must be the **first** JVM argument.
  Without it the process runs cleanly but no window ever appears.
- VS Code's Run/Debug Java tasks work as-is: `vangogh.VanGoghApp` is a plain
  launcher that builds the Krema window and runs the system-webview event loop.
  Add `-XstartOnFirstThread` and `--enable-native-access=ALL-UNNAMED` to the launch task's `vmArgs`.
- The workspace config routes Code Runner's Java Run Code action through
  `mvn compile exec:exec`; do not run `javac VanGoghApp.java` directly, since
  that omits the other project classes and Maven dependencies.
- Main class: `vangogh.app.VanGoghApp`
- IPC: frontend calls `await window.krema.invoke("requestMovies", { kind, query })`;
  handlers are `@KremaCommand` methods on `MovieController` and require
  `<parameters>true</parameters>` in maven-compiler-plugin (args bind by
  parameter name). Posters stream as `window.krema.on("poster", …)` events.

## Vendored repositories (git subtree)

External library source may be vendored under `repos/` via `git subtree` so agents can read real implementation/tests as reference (not `node_modules`/compiled jars).

- Use vendored repos as **read-only reference material** when working with related libraries.
- Prefer examples and patterns from vendored source over generated guesses or web search.
- Do **not** edit files under `repos/` unless explicitly asked.
- Do **not** import from `repos/` — application code imports from normal Maven dependencies.

Setup (when adding a new subtree):

```sh
git subtree add --prefix=repos/<name> <repo-url> <branch> --squash
# update later:
git subtree pull --prefix=repos/<name> <repo-url> <branch> --squash
```

Editor/agent config: exclude `repos/**` from auto-import, file watcher, search, and file explorer (see `.vscode/settings.json`). AGENTS.md should name each vendored repo and its intended usage.

| Vendored repo | Intended usage |
|---|---|
| `repos/krema` (https://github.com/krema-build/krema, `master`) | Read-only reference for `build.krema:krema-core:0.3.2` — inspect real `Krema`, `KremaCommand`, `EventEmitter`, `WebViewCLibEngine` implementations and tests when working with Krema IPC/events/threading. |
