# AGENTS.md — VanGogh

Project rules for VanGogh — entertainment content recommendation desktop app (Java 17 + JavaFX 21 WebView).

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

- Java 17, JavaFX 21.0.2
- `mvn javafx:run` to run the app (official openjfx plugin; handles module path itself)
- VS Code's Run/Debug Java tasks also work as-is: `vangogh.VanGoghApp` is a plain
  launcher (no `extends Application`) that delegates to `VanGoghBootstrap`, so
  classpath launches bypass the JDK "JavaFX runtime components are missing"
  check. Keep that split in mind when touching either class.
- Do not use `mvn exec:exec` here: exec-maven-plugin 3.x does not expand the old
  `%classpath` token in pom `<argument>` elements (it dies with
  "Module javafx.web not found" via the old wrapper). The VS Code extension's
  CLI form `-Dexec.args="-classpath %classpath …"` is fine.
- Main class: `vangogh.VanGoghApp`

## Shared references

- JavaFX: see vault `Agent Docs — javafx.md` if present; shared agent docs under `/Users/macbookpro/Documents/Notes/agent-docs/javafx/`
- VS Code workspace setup: `/Users/macbookpro/Documents/school-project/AGENTS.md`


## Vendored repositories (git subtree)

External library source may be vendored under `repos/` via `git subtree` so agents can read real implementation/tests as reference (not `node_modules`/compiled jars).

- Use vendored repos as **read-only reference material** when working with related libraries.
- Prefer examples and patterns from vendored source over generated guesses or web search.
- Do **not** edit files under `repos/` unless explicitly asked.
- Do **not** import from `repos/` — application code imports from normal Maven dependencies.

### Currently vendored

| Path | Purpose | Status |
|---|---|---|
| `repos/javafx-web` | JavaFX Web (`javafx.web`) reference source (WebView, WebEngine, etc.) | Placeholder repo initialized locally; populate via `git subtree add` when a source URL is chosen |

Setup (when adding a new subtree):

```sh
git subtree add --prefix=repos/<name> <repo-url> <branch> --squash
# update later:
git subtree pull --prefix=repos/<name> <repo-url> <branch> --squash
```

Editor/agent config: exclude `repos/**` from auto-import, file watcher, search, and file explorer (see `.vscode/settings.json`). AGENTS.md should name each vendored repo and its intended usage
