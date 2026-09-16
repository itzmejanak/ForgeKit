plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val developerDocsSource = rootProject.layout.projectDirectory.dir("Docs/developer")
val stagedDeveloperDocs = layout.buildDirectory.dir("generated/developer-docs/assets")
val stagedLegalNotices = layout.buildDirectory.dir("generated/legal-notices/assets")
val forgeKitVersionName = providers.gradleProperty("forgekit.versionName").getOrElse("0.1.1")
val forgeKitVersionCode = providers.gradleProperty("forgekit.versionCode").orNull?.let { raw ->
    raw.toIntOrNull()?.takeIf { it in 1..2_100_000_000 }
        ?: throw GradleException("forgekit.versionCode must be an integer from 1 to 2100000000")
} ?: 10_199
val verifyDeveloperDocs by tasks.registering {
    inputs.dir(developerDocsSource)
    doLast {
        val sourceDir = developerDocsSource.asFile
        val orderFile = sourceDir.resolve("order.txt")
        check(orderFile.isFile) { "Docs/developer/order.txt is required" }
        val order = orderFile.readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
        check(order.isNotEmpty()) { "developer documentation order is empty" }
        check(order.distinct().size == order.size) { "developer documentation order has duplicates" }
        check(order.all { it.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._-]*\\.md$")) }) {
            "developer documentation order contains an unsafe source name"
        }
        val onDisk = sourceDir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "md" }
            .map { it.name }
            .toSet()
        check(onDisk == order.toSet()) {
            "Docs/developer/order.txt drift: missing=${order.toSet() - onDisk}, unlisted=${onDisk - order.toSet()}"
        }
        val merged = order.joinToString("\n\n") { name ->
            sourceDir.resolve(name).readText().replace("\r\n", "\n").replace('\r', '\n').trim()
                .also { check(it.isNotEmpty()) { "developer documentation source is empty: $name" } }
        } + "\n"
        val mandatoryContracts = listOf(
            "forgekit.plugin/v1",
            "forgekit.ui/v1",
            "forgekit/1",
            "prompt_response",
            "Permission and trust enforcement",
            "Handoff checklist",
        )
        val missingContracts = mandatoryContracts.filterNot(merged::contains)
        check(missingContracts.isEmpty()) {
            "developer documentation is missing mandatory contracts: $missingContracts"
        }
    }
}
val stageDeveloperDocs by tasks.registering(Sync::class) {
    dependsOn(verifyDeveloperDocs)
    from(developerDocsSource)
    into(stagedDeveloperDocs.map { it.dir("developer-docs") })
}
val stageLegalNotices by tasks.registering(Sync::class) {
    from(rootProject.layout.projectDirectory.file("LICENSE")) {
        rename { "FORGEKIT-GPL-3.0.txt" }
    }
    from(rootProject.layout.projectDirectory.dir("third-party")) {
        into("third-party")
    }
    into(stagedLegalNotices.map { it.dir("legal") })
}

android {
    namespace = "com.forgekit.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.forgekit.app"
        minSdk = 24
        // Locked at 28: keeps the Android 10+ SELinux policy that allows exec()
        // of binaries stored in app data — the embedded Termux runtime requires it.
        targetSdk = 28
        // Release builds pass both values from the git tag (see Docs/RELEASING.md).
        versionCode = forgeKitVersionCode
        versionName = forgeKitVersionName

        ndk {
            // Locked distribution: arm64-v8a only (user choice)
            abiFilters += "arm64-v8a"
        }
    }

    // The bundled Termux bootstrap is a zip: compressing it again doubles APK size
    // for zero gain (the payload is already DEFLATE inside the archive).
    androidResources {
        noCompress.add("zip")
    }

    lint {
        // targetSdk 28 is a DELIBERATE architecture decision: Android 10+ SELinux
        // forbids exec() of binaries in app data when targetSdk >= 29, and the
        // embedded Termux runtime requires exactly that (same reason upstream
        // Termux pins 28 and ships outside Play). This lint rule is a Play-store
        // policy check, not a correctness check.
        disable += "ExpiredTargetSdkVersion"
    }

    // Release signing material is never stored in the repository. CI (and maintainers)
    // provide it through the environment; see Docs/RELEASING.md.
    val releaseSigning = listOf(
        "FORGEKIT_KEYSTORE_FILE",
        "FORGEKIT_KEYSTORE_PASSWORD",
        "FORGEKIT_KEY_ALIAS",
        "FORGEKIT_KEY_PASSWORD",
    ).associateWith { System.getenv(it)?.takeIf(String::isNotBlank) }
    val hasAnyReleaseSigning = releaseSigning.values.any { it != null }
    val hasReleaseSigning = releaseSigning.values.all { it != null }
    if ((hasAnyReleaseSigning || System.getenv("FORGEKIT_REQUIRE_SIGNING") == "true") && !hasReleaseSigning) {
        throw GradleException(
            "Incomplete release signing configuration; missing: " +
                releaseSigning.filterValues { it == null }.keys.joinToString(),
        )
    }
    if (hasReleaseSigning && !file(releaseSigning.getValue("FORGEKIT_KEYSTORE_FILE")!!).isFile) {
        throw GradleException("FORGEKIT_KEYSTORE_FILE does not name a regular file")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseSigning.getValue("FORGEKIT_KEYSTORE_FILE")!!)
                storePassword = releaseSigning.getValue("FORGEKIT_KEYSTORE_PASSWORD")
                keyAlias = releaseSigning.getValue("FORGEKIT_KEY_ALIAS")
                keyPassword = releaseSigning.getValue("FORGEKIT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false // runtime-critical code; keep symbols intact
            // Without signing variables the release APK is built unsigned (useful for local checks).
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    sourceSets.getByName("main").assets.srcDir(stagedDeveloperDocs)
    sourceSets.getByName("main").assets.srcDir(stagedLegalNotices)

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

tasks.named("preBuild").configure {
    dependsOn(stageDeveloperDocs, stageLegalNotices)
}

tasks.named("check").configure {
    dependsOn(verifyDeveloperDocs)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // composition root: sees every layer (STRUCTURE.md §2)
    implementation(project(":platform:android"))
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:logging"))
    implementation(project(":core:filesystem"))
    implementation(project(":runtime:api"))
    implementation(project(":runtime:bootstrap"))
    implementation(project(":runtime:termux"))
    implementation(project(":termux:embedded"))
    implementation(project(":runtime:bridge"))
    implementation(project(":plugin:api"))
    implementation(project(":plugin:manifest"))
    implementation(project(":plugin:installer"))
    implementation(project(":plugin:validator"))
    implementation(project(":plugin:manager"))
    implementation(project(":plugin:registry"))
    implementation(project(":plugin:protocol"))
    implementation(project(":plugin:resolver"))
    implementation(project(":job:api"))
    implementation(project(":job:manager"))
    implementation(project(":job:persistence"))
    implementation(project(":core:security"))
    implementation(project(":ui:design"))
    implementation(project(":ui:plugins"))
    implementation(project(":ui:terminal"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit5.jupiter)
}
