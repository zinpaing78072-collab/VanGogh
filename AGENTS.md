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
- Workflow: create a feature/fix branch (`feat/<short-name>`, `fix/<short-name>`), commit there, open a PR, merge after review. Keep `main` green.
- Commit messages: short, imperative, lowercase first word (e.g. `add .pen file`, `Fix JavaFX runtime missing error and add run wrapper`). Use conventional-ish style; no strict prefix required.
- Build artifacts (`target/`, `.class`, `.log`, `.DS_Store`) are gitignored — never commit them.
- Run `mvn -q clean compile` (or `mvn test`) before committing; keep the build green.
- Push branches to `origin`; merge into `main` via PR only. Do not rewrite published history on `main` or any shared branch.

## Build / run

- Java 17, JavaFX 21.0.2 (mac classifier), Gson 2.10.1
- `mvn exec:exec` (forked JVM) to run the app
- Main class: `vangogh.VanGoghApp`

## Shared references

- JavaFX: see vault `Agent Docs — javafx.md` if present; shared agent docs under `/Users/macbookpro/Documents/Notes/agent-docs/javafx/`
- VS Code workspace setup: `/Users/macbookpro/Documents/school-project/AGENTS.md`
