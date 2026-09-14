import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.forgekit.runtime.termux"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        ndk {
            // Locked distribution: arm64-v8a only (user choice)
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }

    // Tests run on the host JVM: they need the host PTY .so from :termux:embedded.
    // The Android .so cannot be loaded on the host, so we compile the same C file
    // here for host execution exactly like termux/embedded does for its own tests.
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":runtime:api"))
    api(project(":runtime:bridge"))
    api(project(":runtime:bootstrap"))
    api(project(":termux:embedded"))
    implementation(project(":core:filesystem"))
    implementation(project(":core:logging"))
    implementation(project(":core:model"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    // Real PTY channel tests run against the host harness compiled by :termux:embedded
    testImplementation(project(":termux:embedded"))
}

// ---------------------------------------------------------------------------
// Host-side PTY library for JVM unit tests: the SAME forgekit_pty.c compiled
// with the host toolchain. Located via the task below so tests can load it.
// ---------------------------------------------------------------------------
val hostPtyDir = layout.buildDirectory.dir("host-pty")

val compileHostPty by tasks.registering(Exec::class) {
    val javaHome = providers.systemProperty("java.home").get()
    val src = rootProject.layout.projectDirectory
        .file("termux/embedded/src/main/c/forgekit_pty.c").asFile
    val out = hostPtyDir.map { it.file("libforgekit_pty_host.so") }
    commandLine(
        "cc",
        "-shared", "-fPIC", "-O2", "-Wall", "-Wextra",
        "-I$javaHome/include",
        "-I$javaHome/include/linux",
        src.absolutePath,
        "-o", out.get().asFile.absolutePath,
        "-lutil",
    )
    inputs.file(src)
    outputs.file(out)
}

tasks.matching { it.name.endsWith("UnitTest") }.configureEach {
    dependsOn(compileHostPty)
}
