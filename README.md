# Mirage — runtime gRPC mock engine

Mirage lets **debug builds** override specific unary gRPC responses at runtime by dropping a JSON
file on the device — no rebuild, no app restart. Default behavior is the real server; only RPCs that
have a mock file are overridden. Real responses passing through are captured into a *corpus* so mocks
can be grounded on real data instead of hallucinated.

It's a tiny, **app-agnostic** library: it operates on `io.grpc.MethodDescriptor` + protobuf at
runtime and has no dependency on any specific app.

## Install

> **Needs:** Android **minSdk 28+**, and an app already using **gRPC + protobuf**. Mirage declares
> `grpc-api` / `protobuf-java` / `protobuf-java-util` as `compileOnly`, so it reuses **your** versions
> — no extra weight, no version conflict.

### Easiest — let your AI agent do it

Tell your AI coding agent (Claude Code, Cursor, …):

> "Install Mirage into my app by following
> https://raw.githubusercontent.com/tedKim5178/mirage/main/INSTALL.md"

[INSTALL.md](INSTALL.md) is written for an agent: it finds your gRPC channel class, adds the
dependency, wires the one interceptor line, installs the mock-authoring skill, and builds to verify.
Nothing else for you to do.

### By hand — 3 steps

1. **JitPack repository** — in `settings.gradle.kts` (`dependencyResolutionManagement`) or your root build:
   ```kotlin
   repositories { maven { url = uri("https://jitpack.io") } }
   ```
2. **Dependency** — in the build.gradle.kts of the module that builds your gRPC channel:
   ```kotlin
   implementation("com.github.tedKim5178:mirage:1.0.0")
   ```
3. **One line** — where you build your `ManagedChannel`, guarded to debug:
   ```kotlin
   channelBuilder.apply { if (BuildConfig.DEBUG) intercept(Mirage.interceptor) }
   ```

That's all the code. A debug-guarded `ContentProvider` bundled in the library auto-registers via
manifest merging and initializes Mirage at startup — **no `Application` change, no manifest entry, no
init call.** Mirage stays completely dormant in release builds. (Want it fully absent from release?
Put your channel-attach code in a debug source set and use `debugImplementation`.)

## Your first mock

1. **Capture.** Build & run the **debug** app on a device/emulator and open the screen you want to
   fake. Mirage silently records the real responses into its *corpus* — that's what your mock is
   grounded on, so it looks real.
2. **Ask.** Tell your AI in plain language: *"show this screen as if a card is registered."* Using the
   bundled `mirage-mock` skill, it finds the right RPC from the corpus, writes the mock JSON, and
   `adb`-pushes it to the device.
3. **See it.** Refresh / re-enter the screen → it now shows the mocked state. To revert, delete the
   file (or just say *"turn the mock off"*).

No AI agent or device handy? The exact same workflow done by hand is in
[`mirage-mock/SKILL.md`](mirage-mock/SKILL.md).

## Installing the mock-authoring skill manually

The AI installer above copies it for you. To add it by hand so Claude Code can author mocks in your
project:
```bash
mkdir -p .claude/skills/mirage-mock
curl -sL https://raw.githubusercontent.com/tedKim5178/mirage/main/mirage-mock/SKILL.md \
  -o .claude/skills/mirage-mock/SKILL.md
```

## What is / isn't supported

- **Unary only.** Server/bidi-streaming always passes through (not mockable). Polling ≈ repeated
  unary and is handled by the per-call file read.
- **Per-call read.** Editing/pushing a mock file applies from the next RPC — no restart.
- **Off = delete the file.**
- **Debug only.** The interceptor is only attached under `BuildConfig.DEBUG`; the auto-installer only
  arms on a debuggable host build.
- **Corpus holds real responses (incl. PII).** Internal debug use only; never ship or send corpus
  contents to an external service unmasked.

## Building / publishing this library

See [PUBLISHING.md](PUBLISHING.md) for how this repo is published to JitPack (standalone Gradle setup,
`maven-publish`, tagging a release).
