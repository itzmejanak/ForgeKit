plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
}

dependencies {
    api(project(":core:common"))
    api(project(":core:model"))
    api(project(":core:filesystem"))
    api(project(":core:security"))
    api(project(":plugin:api"))
    api(project(":plugin:manifest"))
    implementation(libs.kotlinx.serialization.json)
    testFixturesImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit5.jupiter)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}
