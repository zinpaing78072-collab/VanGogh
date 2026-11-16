---
title: "Debug Record — Compile errors masked as ConcurrentModificationException (JDK 25 in-process javac)"
date: 2026-11-16
project: "[[VanGogh]]"
tags: [debug-record, maven, jdk25, build]
status: workaround-identified
---

# Debug Record — Compile errors masked as ConcurrentModificationException (JDK 25 in-process javac)

## Symptom

`mvn clean compile` / `mvn clean test` on Windows (JDK 25.0.4, Maven 3.9.9,
maven-compiler-plugin 3.11.0) fails with:

```
Fatal error compiling: CompilerException: ConcurrentModificationException
```

No real javac errors are shown. Repro is deterministic for a tree with
compilation errors; a tree that compiles cleanly builds fine (which makes the
failure look like "my code crashed javac").

## Root cause

maven-compiler-plugin 3.11.0 compiles **in-process** via plexus-compiler
`JavaxToolsCompiler`: after `task.call()` returns, it iterates the
`DiagnosticCollector`'s diagnostics list. With JDK 25's javac, real compile
errors arrive (or the list is touched) such that the iteration hits a modified
ArrayList — `ConcurrentModificationException` from
`JavaxToolsCompiler.compileInProcess` — and plexus wraps that as the *only*
reported failure. The actual compile errors are swallowed.

Stack signature:

```
Caused by: java.util.ConcurrentModificationException
    at java.util.ArrayList$Itr.checkForComodification
    at java.util.Collections$UnmodifiableCollection$1.next
    at org.codehaus.plexus.compiler.javac.JavaxToolsCompiler.compileInProcess(JavaxToolsCompiler.java:139)
```

## Workaround

Pass `-Dmaven.compiler.fork=true` to run javac out-of-process; real errors
then print normally:

```
mvn clean compile -Dmaven.compiler.fork=true
```

## Proper fix (not yet applied)

Upgrade `maven-compiler-plugin` to a release carrying the newer
plexus-compiler, or add `<fork>true</fork>` to the pom. Both are pom changes
and were deliberately not made inside the music/book slices (out of slice
scope); the tree compiles cleanly without them.

## Rule of thumb

On this machine, if a Maven build dies with
`CompilerException: ConcurrentModificationException`, **the code almost
certainly has real compile errors** — re-run with
`-Dmaven.compiler.fork=true` to see them. Do not "fix" the CME itself.
