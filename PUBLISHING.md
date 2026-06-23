# Publishing Mirage to JitPack

Mirage is published as a standalone, **public** GitHub repo that [JitPack](https://jitpack.io) builds
on demand. JitPack builds a whole GitHub repo at a git tag, so Mirage lives in its **own small repo**
(not inside a large app monorepo, which JitPack would fail to build). This guide is written for a
first-time publisher.

> The engine has zero dependency on any app, so extracting it is just "copy these files into a fresh
> repo + add a standalone Gradle build + tag a release."

---

## What goes into the new repo

From the current `mirage/` module, copy:

```
src/                      # engine + provider + manifest + tests (unchanged)
mirage-mock/SKILL.md      # Claude Code skill (already genericized for public)
README.md                 # consumer-facing docs (already rewritten)
jitpack.yml               # JDK 17 for the JitPack build
PUBLISHING.md             # this file
```

Then **replace** the build script and **add** a settings file with the two standalone versions below
(the in-monorepo `build.gradle.kts` uses the app's shared version catalog and plugin classpath, which
don't exist in a standalone repo — so it must be swapped).

---

## Step 1 — Create the repo

Create a new **public** GitHub repo named **`mirage`** (the repo name becomes the artifact name in the
JitPack coordinate `com.github.<user>:mirage:<tag>`). Don't add a license/readme from the UI — you'll
push your own.

## Step 2 — Copy the files

Copy the files listed above into a local clone of the new repo.

## Step 3 — Add `settings.gradle.kts` (new file at repo root)

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "mirage"
```

## Step 4 — Replace `build.gradle.kts` (standalone version)

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library") version "8.13.0"
    id("org.jetbrains.kotlin.android") version "2.2.20"
    id("maven-publish")
}

android {
    namespace = "com.hyundai.airlab.mirage"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Flavor-agnostic single variant + a sources jar for consumers.
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    // Run the JUnit5 unit tests on the JVM without an extra plugin.
    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Provided by the host app at runtime — Mirage uses the app's versions (no conflict).
    compileOnly("io.grpc:grpc-api:1.76.0")
    compileOnly("com.google.protobuf:protobuf-java:4.33.0")
    compileOnly("com.google.protobuf:protobuf-java-util:4.33.0")

    implementation("com.jakewharton.timber:timber:5.0.1")

    testImplementation("io.grpc:grpc-api:1.76.0")
    testImplementation("com.google.protobuf:protobuf-java:4.33.0")
    testImplementation("com.google.protobuf:protobuf-java-util:4.33.0")
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.0")
}

// JitPack publishes the 'release' AAR (+ sources) as com.github.<user>:mirage:<tag>.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.tedKim5178"
                artifactId = "mirage"
                version = "1.0.0"
            }
        }
    }
}
```

Replace `tedKim5178` with your GitHub username/org. (JitPack ultimately serves it under
`com.github.<user>`, so this is mostly cosmetic, but keep it consistent.)

## Step 5 — Add the Gradle wrapper

JitPack runs `./gradlew`, so the repo needs a wrapper. Easiest: copy these four from any existing
project (e.g. this monorepo) into the new repo, keeping paths:

```
gradlew
gradlew.bat
gradle/wrapper/gradle-wrapper.jar
gradle/wrapper/gradle-wrapper.properties
```

(Or, if you have Gradle installed: `gradle wrapper --gradle-version 8.14.3`.)

Add a `.gitignore`:
```
.gradle/
build/
local.properties
.idea/
*.iml
```

## Step 6 — Verify locally (optional but recommended)

```bash
./gradlew clean :test publishToMavenLocal
```
- `:test` runs the unit tests.
- `publishToMavenLocal` is exactly what JitPack does — if this succeeds, JitPack will too.

## Step 7 — Commit, tag, push

```bash
git add -A
git commit -m "Mirage 1.0.0 — standalone gRPC mock engine"
git tag 1.0.0
git push origin main --tags
```

## Step 8 — Trigger the JitPack build

Open `https://jitpack.io/#tedKim5178/mirage`, find tag `1.0.0`, and click **Get it**. JitPack
builds the tag and shows a green log when the artifact is ready. (First build takes a few minutes.)

## Step 9 — Consume it

In any app:
```kotlin
// settings.gradle.kts
repositories { maven { url = uri("https://jitpack.io") } }

// module build.gradle.kts
implementation("com.github.tedKim5178:mirage:1.0.0")
```
Then the one integration line (see [README.md](README.md)).

### Switching a monorepo off an in-repo module (later)

If you extracted Mirage from an app monorepo, you can drop the in-repo module and consume the
published artifact instead:
1. Remove `include(":mirage")` from the app's `settings.gradle.kts`.
2. Replace `implementation(project(":mirage"))` in the consuming module with
   `implementation("com.github.tedKim5178:mirage:1.0.0")`.
3. Delete the in-repo `mirage/` directory (and any catalog alias you added for it).

## Releasing a new version

Commit changes → `git tag 1.1.0` → push the tag → consumers bump the version. (Update `version` in
`build.gradle.kts` to match if you like; JitPack uses the tag regardless.)
