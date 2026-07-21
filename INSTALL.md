# Installing Mirage — instructions for an AI coding agent

> **This file is written for an AI assistant** (Claude Code, Cursor, etc.), not (only) a human.
> A human can install Mirage into their Android app by telling their agent:
>
> > "Install Mirage into my app by following
> > https://raw.githubusercontent.com/tedKim5178/mirage/main/INSTALL.md"
>
> The agent then performs every step below itself. Mirage is a **debug-only gRPC mock engine**: it
> overrides specific unary gRPC responses at runtime from a JSON file on the device.

You (the agent) make **three changes**, all of which you can do by inspecting the project — the human
should not have to hand you file paths. Discover them, make the edits, then build to verify. If
anything is ambiguous, ask the human the one specific question rather than guessing.

---

## Step 0 — Preconditions (check, don't assume)

1. **It's a gRPC Android app.** Confirm `io.grpc` is used (grep for `io.grpc`, `ManagedChannel`,
   `ManagedChannelBuilder`). If the app does **not** use gRPC, stop and tell the human — Mirage only
   mocks gRPC.
2. **minSdk ≥ 28.** Find the app module's `minSdk`. If it's below 28, warn the human (Mirage's
   `minSdk` is 28; either they raise theirs or this won't apply).

---

## Step 1 — Repository + dependency

**1a. Ensure the JitPack repository is available.** In `settings.gradle.kts` (inside
`dependencyResolutionManagement { repositories { … } }`) — or the root `build.gradle` for older setups
— make sure this exists, and add it if missing:
```kotlin
maven { url = uri("https://jitpack.io") }
```

**1b. Add the dependency to the module that builds the gRPC channel.** (You locate that module in
Step 2 — the dependency goes in *that* module's `build.gradle.kts`, because that's where
`Mirage.interceptor` is referenced.)
```kotlin
implementation("com.github.tedKim5178:mirage:1.2.0")
```
Use `implementation` (not `debugImplementation`): the interceptor is referenced from main code guarded
by `BuildConfig.DEBUG`, and Mirage stays completely dormant in release anyway. Mirage pulls in nothing
heavy — `grpc`/`protobuf` are `compileOnly`, so it reuses the app's existing versions.

---

## Step 2 — Attach the interceptor (the only code line)

**2a. Find the class that builds the `ManagedChannel`.** Grep for these and read the hit:
`ManagedChannelBuilder`, `OkHttpChannelBuilder`, `AndroidChannelBuilder`, `.forAddress(`,
`.forTarget(`, an existing `.intercept(`, or `: ManagedChannel`. There is usually exactly one such
builder site.

**2b. Add a debug-guarded interceptor on that builder, just before `.build()`.** Add the import and
the one line:
```kotlin
import com.hyundai.airlab.mirage.Mirage
// …
channelBuilder.apply { if (BuildConfig.DEBUG) intercept(Mirage.interceptor) }
```
- Use the `BuildConfig` of the module you are editing (its own namespace's `BuildConfig`). If the
  channel class's package differs from that module's namespace, add the explicit import
  `import <module-namespace>.BuildConfig` (e.g. `import com.example.myapp.network.BuildConfig`); if the
  class already sits in the namespace's root package, `BuildConfig` resolves without an import.
- That module must have BuildConfig enabled. If it doesn't, add `android { buildFeatures { buildConfig = true } }`. (Most app/server modules already have it.)
- **Do not touch the data layer / repositories / DI.** The only change is this one line at channel-build time.
- If the builder isn't a fluent chain you can `.apply { … }` on, just call `channelBuilder.intercept(Mirage.interceptor)` (debug-guarded) wherever the builder is configured before `build()`.

**That's all the code.** There is **no** init call, **no** `Application` change, and **no** manifest
edit: Mirage ships a debug-guarded `ContentProvider` inside its AAR that auto-registers via manifest
merging and initializes the engine at startup. It self-disables on non-debuggable builds.

**2c. (Only if the app uses the rich error model) register its error-detail proto.** This enables
*typed* error mocks (`$mirageError` envelopes whose `details` carry the app's custom error proto —
see the skill). Check whether the app unpacks a custom proto from gRPC error details:
`grep -rn "StatusProto.fromThrowable" --include='*.kt'` — the converter it leads to unpacks some
proto (e.g. `commonv1.ErrorInfo`). If found, add one debug-guarded line next to the interceptor line:
```kotlin
if (BuildConfig.DEBUG) Mirage.registerErrorDetailTypes(ErrorInfo.getDefaultInstance())
```
(using that proto's generated class). If the app has no such converter, skip this — `code`-only
error mocks work with no registration.

---

## Step 3 — Install the mock-authoring skill (optional but recommended)

So the human's AI can later turn "show this screen as if a card is registered" into an applied mock,
drop the bundled skill into the repo:
```bash
mkdir -p .claude/skills/mirage-mock
curl -sL https://raw.githubusercontent.com/tedKim5178/mirage/main/mirage-mock/SKILL.md \
  -o .claude/skills/mirage-mock/SKILL.md
```

---

## Step 4 — Verify

Build the app's **debug** variant (pick the real task name from the project — e.g.
`./gradlew :app:assembleDebug`, or a flavored one like `:app:assembleDevDebug`). Confirm it compiles
and packages. Optionally check the merged debug manifest contains
`com.hyundai.airlab.mirage.MirageAutoInstaller` (proof the auto-installer merged from the AAR).

Then tell the human: Mirage is installed. At runtime (debug), real responses are auto-captured into a
corpus under the app's external files dir, and they can ask you to mock any screen state from natural
language using `mirage-mock/SKILL.md`.

---

## Summary of what you changed

| Change | Where |
|---|---|
| JitPack repo (if missing) | `settings.gradle.kts` |
| `implementation("com.github.tedKim5178:mirage:1.2.0")` | build.gradle.kts of the channel module |
| `if (BuildConfig.DEBUG) intercept(Mirage.interceptor)` + import | the channel-building class |
| `registerErrorDetailTypes(...)` (only rich-error-model apps) | next to the interceptor line |
| `mirage-mock/SKILL.md` | `.claude/skills/mirage-mock/` |

Everything else (startup init, the mock/corpus directory, the provider registration) is handled by the
published library itself.
