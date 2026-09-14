plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
}

dependencies {
    api(project(":core:common"))
    api(project(":core:model"))
    api(project(":plugin:api"))
    api(project(":plugin:manifest"))
    api(project(":runtime:api"))
    testFixturesImplementation(project(":core:filesystem"))
    testFixturesImplementation(libs.kotlinx.coroutines.core)
    testImplementation(project(":core:filesystem"))
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}
