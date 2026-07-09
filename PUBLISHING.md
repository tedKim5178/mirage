# Publishing Mirage to JitPack

Mirage is published as a standalone, **public** GitHub repo that [JitPack](https://jitpack.io) builds
on demand at a git tag (`github.com/tedKim5178/mirage`). This guide covers the repo layout, why it is
shaped that way, and how to cut a new release.

---

## Layout — two single-variant modules

```
build.gradle.kts        # root container: declares plugin versions (apply false)
settings.gradle.kts     # include(":mirage", ":mirage-debug")
jitpack.yml             # JDK 17 for the JitPack build
mirage/                 # the engine  → artifact "mirage"
mirage-debug/           # installer + HTTP control server → artifact "mirage-debug"
mirage-mock/SKILL.md    # Claude Code skill for authoring mocks
README.md, INSTALL.md   # consumer-facing docs
```

A consumer wires both:
```kotlin
implementation("com.github.tedKim5178.mirage:mirage:<tag>")            // engine
debugImplementation("com.github.tedKim5178.mirage:mirage-debug:<tag>") // installer + control server
```

### Why two modules (and not one multi-variant library)

The goal is that the HTTP control server + nanohttpd ship in **debug builds only** and are absent from
release. The obvious way — one library that publishes a debug AAR (with server) and a release AAR
(engine only) via `multipleVariants` — **does not work on JitPack**: JitPack rewrites the Gradle
module metadata and strips the per-variant AAR classifiers, so the published `.module` points at a
`mirage-<tag>.aar` that doesn't exist and consumers fail to resolve it (this is exactly why 1.1.0 was
broken).

The fix is to split by module instead of by variant. Each module is a plain **single-variant**
(`singleVariant("release")`) library, which JitPack publishes cleanly. The debug/release split then
lives on the **consumer** side via `implementation` vs `debugImplementation`. In release, the engine
is only referenced from a `BuildConfig.DEBUG` branch, so R8 strips it too.

### Coordinate note (multi-module group)

JitPack serves a **multi-module** repo as `com.github.<user>.<repo>:<module>` — so the repo name is
part of the group. Here that is `com.github.tedKim5178.mirage`, with artifacts `mirage` and
`mirage-debug`. (A single-module repo would instead be `com.github.<user>:<repo>`, which is what
1.0.0 used before the split.) The `groupId` set in each module's publication is kept in sync with this
so that `includeBuild("../mirage")` composite builds and `publishToMavenLocal` match what JitPack
serves.

---

## Cutting a new release

1. **Bump the version** in both modules — `version = "X.Y.Z"` and the `create<MavenPublication>`
   `version` in `mirage/build.gradle.kts` and `mirage-debug/build.gradle.kts`. Update the coordinates
   shown in `README.md` / `INSTALL.md` too.
2. **Verify locally** (this is exactly what JitPack runs):
   ```bash
   ./gradlew :mirage:testDebugUnitTest publishToMavenLocal
   ```
   Both `mirage` and `mirage-debug` should appear under
   `~/.m2/repository/com/github/tedKim5178/mirage/`.
3. **Commit, tag, push:**
   ```bash
   git commit -am "Mirage X.Y.Z — <summary>"
   git tag X.Y.Z
   git push origin main X.Y.Z
   ```
4. **Trigger the build.** JitPack builds lazily on first fetch; warm both artifacts:
   ```bash
   curl -s https://jitpack.io/com/github/tedKim5178/mirage/mirage/X.Y.Z/mirage-X.Y.Z.pom > /dev/null
   curl -s https://jitpack.io/com/github/tedKim5178/mirage/mirage-debug/X.Y.Z/build.log | tail
   ```
   A green log ending in `Build artifacts:` means both AARs are ready.

> **Tags are immutable on JitPack.** If a tagged build is broken, you cannot fix it under the same tag
> — cut a new version. (That is why the broken 1.1.0 was superseded by 1.1.1 rather than re-pushed.)

---

## Local development against a consumer (no publish)

A consumer can build Mirage straight from a sibling checkout instead of JitPack, so engine/server edits
apply on the next consumer build with no tag:

```kotlin
// consumer settings.gradle.kts (guard behind a gitignored flag so it never lands in CI)
includeBuild("../mirage") {
    dependencySubstitution {
        substitute(module("com.github.tedKim5178.mirage:mirage")).using(project(":mirage"))
        substitute(module("com.github.tedKim5178.mirage:mirage-debug")).using(project(":mirage-debug"))
    }
}
```
