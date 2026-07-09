import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library") version "8.13.0"
    id("org.jetbrains.kotlin.android") version "2.2.20"
    id("maven-publish")
}

// Project coordinates. These make composite builds work: when a consumer does
// includeBuild("../mirage"), Gradle substitutes any "com.github.tedKim5178:mirage"
// dependency with this local build by matching group:name (version is ignored).
group = "com.github.tedKim5178"
version = "1.1.0"

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

    // Publish BOTH variants so a consumer's debug build variant-matches the debug AAR (which carries
    // the control server + installer from src/debug), while release matches the clean engine-only AAR.
    publishing {
        multipleVariants("all") {
            allVariants()
            withSourcesJar()
        }
    }

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

    // Debug-only HTTP control server — present in the debug variant only, never release.
    debugImplementation("org.nanohttpd:nanohttpd:2.3.1")

    testImplementation("io.grpc:grpc-api:1.76.0")
    testImplementation("com.google.protobuf:protobuf-java:4.33.0")
    testImplementation("com.google.protobuf:protobuf-java-util:4.33.0")

    // JUnit BOM keeps jupiter + platform-launcher versions aligned (fixes engine-discovery mismatch).
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// JitPack publishes both variants (Gradle module metadata) as com.github.tedKim5178:mirage:<tag>;
// consumers variant-match debug↔debug (server included) and release↔release (engine only).
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("all") {
                from(components["all"])
                groupId = "com.github.tedKim5178"
                artifactId = "mirage"
                version = "1.1.0"
            }
        }
    }
}
