---
title: "Debug Record — mvn compile dies with ConcurrentModificationException on Windows + JDK 25"
date: 2026-11-16
project: "[[VanGogh]]"
tags: [debug-record, windows, jdk25, maven, build]
status: worked-around
---

# Debug Record — mvn compile dies with ConcurrentModificationException on Windows + JDK 25

## Symptom

On Windows, building VanGogh with JDK 25 fails before compiling a single
class:

```
[ERROR] Fatal error compiling: CompilerException:
    java.util.ConcurrentModificationException
```

No source file is named; the build dies inside the compiler plugin. The same
pom compiles fine on the macOS machines.

## Environment

- Windows 11, Oracle JDK 25 (25.0.5), Maven 3.10.1
- maven-compiler-plugin 3.11.0 (pinned in pom.xml)
- Repro 100%: `mvn clean compile` with `JAVA_HOME` at JDK 25
- macOS unaffected (the team's normal build env)

## Root cause

maven-compiler-plugin 3.11.0 runs javac **in-process** via
plexus-compiler's `JavaxToolsCompiler`, which iterates a shared compiler
argument list that newer JDK javac toolchain calls mutate mid-iteration →
`ArrayList$Itr.checkForComodification`. The bug lives in the old bundled
plexus-compiler (fixed upstream in plexus-compiler 2.15.x, pulled in by
maven-compiler-plugin 3.13+); the JDK is merely the trigger.

## Workaround (no pom change)

Run the compiler **forked**, which bypasses the in-process
`JavaxToolsCompiler` entirely and shells out to `${java.home}/bin/javac`:

```sh
mvn clean compile -Dmaven.compiler.fork=true
mvn test -Dmaven.compiler.fork=true
```

`fork` is not configured in the pom, so the CLI property applies without
touching shared files (the pom keeps `<release>25</release>` and
`<parameters>true</parameters>` either way — forked javac honors both).

## Real fix (when the team wants it)

Bump maven-compiler-plugin to 3.13.0+ (newer plexus-compiler). Not done in
the book slice: the pom is shared build infra and macOS builds are green on
3.11.0, so the bump belongs to its own build-infra slice with a green CI
run.

## Lessons / conventions

- On this Windows machine, always pass `-Dmaven.compiler.fork=true` to every
  mvn invocation until the plugin is bumped.
- If a "Fatal error compiling" names no source file, suspect the toolchain
  (plugin × JDK combination) before suspecting the code — run with `-e` and
  look at the plexus/javac frames.
- Windows dev environments need their own JDK 25 + Maven install (none is
  preinstalled); JDK 24 on PATH is not enough because the pom compiles with
  `--release 25`.
