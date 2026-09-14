import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.forgekit.termux.embedded"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        ndk {
            // Locked distribution: arm64-v8a only (the bundled Termux bootstrap is arm64)
            abiFilters += "arm64-v8a"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/c/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core:common"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit5.jupiter)
}

// ---------------------------------------------------------------------------
// Host-side compilation of the SAME C source (real fork/exec/pty on the host
// JVM) so unit tests prove the harness without a device. The production .so
// for Android comes from externalNativeBuild above — identical forgekit_pty.c.
// ---------------------------------------------------------------------------
val hostPtyDir = layout.buildDirectory.dir("host-pty")

val compileHostPty by tasks.registering(Exec::class) {
    val javaHome = providers.systemProperty("java.home").get()
    val out = hostPtyDir.map { it.file("libforgekit_pty_host.so") }
    commandLine(
        "cc",
        "-shared", "-fPIC", "-O2", "-Wall", "-Wextra",
        "-I$javaHome/include",
        "-I$javaHome/include/linux",
        file("src/main/c/forgekit_pty.c").absolutePath,
        "-o", out.get().asFile.absolutePath,
        "-lutil",
    )
    inputs.file("src/main/c/forgekit_pty.c")
    outputs.file(out)
}

tasks.matching { it.name.endsWith("UnitTest") }.configureEach {
    dependsOn(compileHostPty)
}
