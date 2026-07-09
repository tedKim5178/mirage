// Root is a container for two published modules:
//   :mirage        → artifact "mirage"        — the engine (interceptor). Consumers use implementation().
//   :mirage-debug  → artifact "mirage-debug"  — installer + HTTP control server. Consumers use
//                                                debugImplementation(), so it is absent from release.
// Plugin versions are declared here (apply false) and applied without a version in each module.
plugins {
    id("com.android.library") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
}
