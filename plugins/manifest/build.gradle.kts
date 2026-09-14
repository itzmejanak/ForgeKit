plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:common"))
    api(project(":core:model"))
    api(project(":plugin:api"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit5.jupiter)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}
