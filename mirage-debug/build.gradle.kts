import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

// Published as com.github.tedKim5178:mirage-debug. Consumers add it with debugImplementation(), so
// the installer + control server (and nanohttpd) are compiled into the app's debug build only and
// are entirely absent from release.
group = "com.github.tedKim5178"
version = "1.1.1"

android {
    namespace = "com.hyundai.airlab.mirage.debug"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // The engine, whose public API this module drives (Mirage.init / store / corpus). A consumer
    // declares mirage itself with implementation(); this dependency keeps the two versions aligned.
    implementation(project(":mirage"))

    // HTTP control server. Real runtime dependency — ships inside this debug-only artifact.
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // The engine's public surface references grpc types (Mirage.interceptor); needed to compile
    // against it. Provided by the host app at runtime.
    compileOnly("io.grpc:grpc-api:1.76.0")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.tedKim5178"
                artifactId = "mirage-debug"
                version = "1.1.1"
            }
        }
    }
}
