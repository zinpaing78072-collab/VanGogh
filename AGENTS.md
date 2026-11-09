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

- Java 25 (LTS), JavaFX 25.0.5. Compiling requires a JDK 25+ install (pom compiles with `--release 25`); install JDK 25 when necessary.
- `mvn javafx:run` to run the app (official openjfx plugin; handles module path itself).
- VS Code's Run/Debug Java tasks also work as-is: `vangogh.VanGoghApp` is a plain launcher (no `extends Application`) that delegates to `VanGoghBootstrap`, so classpath launches bypass the JDK "JavaFX runtime components are missing" check. Keep that split in mind when touching either class.
- Do not use `mvn exec:exec` here: exec-maven-plugin 3.x does not expand the old `%classpath` token in pom `<argument>` elements (it dies with "Module javafx.web not found" via the old wrapper). The VS Code extension's CLI form `-Dexec.args="-classpath %classpath …"` is fine.
- Main class: `vangogh.VanGoghApp`
- IPC: frontend calls `await window.krema.invoke("requestMovies", { kind, query })`;
  handlers are `@KremaCommand` methods on `MovieController` and require
  `<parameters>true</parameters>` in maven-compiler-plugin (args bind by
  parameter name). Posters stream as `window.krema.on("poster", …)` events.

## LLM engine

- Package `vangogh.llm` — OpenAI-compatible chat-completion engine.
- `LlmConfig` reads `LLM_API_KEY` (required), `LLM_BASE_URL` (default
  `https://api.openai.com/v1`), `LLM_MODEL` (default `gpt-3.5-turbo`),
  `LLM_SYSTEM_PROMPT`, `LLM_TIMEOUT_SECONDS` (default 30) from the environment.
- `LlmClient` POSTs `{baseUrl}/chat/completions` over `java.net.http.HttpClient`;
  returns the assistant message content, or `null` if unconfigured or the call
  fails. Never touches the network when `LLM_API_KEY` is unset.
- `LlmService#recommend(query)` wraps `LlmClient` and parses the LLM's JSON array
  into `Recommendation` records (kind, title, reason). Gracefully degrades to an
  empty list on bad input / LLM unavailable — the UI never breaks because of the AI layer.
- Frontend: `window.krema.invoke("recommend", { query })` returns
  `List<Recommendation>`. UI component is the "✨ AI Recommendations" button in the
  header; Ctrl+Enter on the search input also triggers it.
- No new external dependencies: the engine reuses the existing
  `java.net.http.HttpClient` + Gson only.
- Build: `mvn -q clean compile` and `mvn test` must stay green.

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
