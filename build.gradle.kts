// ForgeKit root build file.
// Module-specific plugins are applied in each module's build.gradle.kts (STRUCTURE.md §4).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

subprojects {
    // Unique Maven coordinates per module: sibling modules that share a leaf
    // name (:runtime:api vs :plugin:api, :plugin:api vs :job:api …) would
    // otherwise collide on `com.forgekit:api` and Gradle would silently
    // substitute one project for the other wherever both appear on a
    // classpath. Deriving the group from the parent path keeps every module
    // distinct: com.forgekit.runtime:api, com.forgekit.plugin:api, …
    val parentPath = project.path.removePrefix(":").substringBeforeLast(':').replace(':', '.')
    group = if (parentPath.isEmpty()) "com.forgekit" else "com.forgekit.$parentPath"
    // One project version for the app and the command-line tools; releases pass the tag's version.
    version = providers.gradleProperty("forgekit.versionName").getOrElse("0.1.0")

    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging {
            events("failed", "skipped")
            showStandardStreams = false
        }
        maxParallelForks = 1
    }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            freeCompilerArgs.set(listOf("-Xjsr305=strict"))
        }
    }
}
