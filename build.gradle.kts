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

    // JUnit BOM keeps jupiter + platform-launcher versions aligned (fixes engine-discovery mismatch).
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// JitPack publishes the 'release' AAR (+ sources) as com.github.tedKim5178:mirage:<tag>.
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
