# Mirage — runtime gRPC mock engine

Mirage lets **debug builds** override specific unary gRPC responses at runtime by dropping a JSON
file on the device — no rebuild, no app restart. Default behavior is the real server; only RPCs that
have a mock file are overridden. Real responses passing through are captured into a *corpus* so mocks
can be grounded on real data instead of hallucinated.

It's a tiny, **app-agnostic** library: it operates on `io.grpc.MethodDescriptor` + protobuf at
runtime and has no dependency on any specific app.

## Requirements

- Android **minSdk 28+**
- Your app already uses **gRPC + protobuf**. Mirage declares `grpc-api`, `protobuf-java`, and
  `protobuf-java-util` as `compileOnly`, so it uses **your** versions — any gRPC app already ships
  them, and there is never a version conflict.

## Install (JitPack)

1. Add JitPack as a repository (in `settings.gradle.kts` `dependencyResolutionManagement`, or your
   root build):
   ```kotlin
   repositories {
       maven { url = uri("https://jitpack.io") }
   }
   ```
2. Add the dependency, using a released git tag as the version:
   ```kotlin
   implementation("com.github.tedKim5178:mirage:1.0.0")
   ```
   > Mirage ships in release builds too but is **completely dormant** there — the interceptor is only
   > attached under `BuildConfig.DEBUG` (see below) and the auto-installer no-ops on non-debuggable
   > builds. If you'd rather it be fully absent from release, put your channel-attach code in a debug
   > source set and use `debugImplementation` instead.

## Integrate — one line

Attach the interceptor wherever you build your `ManagedChannel`, guarded to debug:
```kotlin
channelBuilder.apply { if (BuildConfig.DEBUG) intercept(Mirage.interceptor) }
```
**That is the only code you write.** A debug-guarded `ContentProvider` bundled in the library
auto-registers via manifest merging and initializes Mirage at startup — no `Application` changes, no
manifest entry, no init call. (The mock/corpus directory is created automatically under the app's
external files dir.)

## Authoring mocks (optional — Claude Code)

The bundled [`mirage-mock/SKILL.md`](mirage-mock/SKILL.md) is both a human guide and a Claude Code
skill for turning a natural-language request ("show this screen as if a card is registered") into an
applied mock — it finds the RPC, grounds on a captured corpus sample, writes the JSON, and `adb`
pushes it.

To let Claude Code author mocks in your project, drop the skill into your repo's `.claude/skills/`:
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
- **Debug only.** The interceptor is only attached under `BuildConfig.DEBUG`; the auto-installer
  only arms on a debuggable host build.
- **Corpus holds real responses (incl. PII).** Internal debug use only; never ship or send corpus
  contents to an external service unmasked.

## Building / publishing this library

See [PUBLISHING.md](PUBLISHING.md) for how this repo is published to JitPack (standalone Gradle
setup, `maven-publish`, tagging a release).
