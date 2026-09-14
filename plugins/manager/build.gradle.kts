plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:common"))
    api(project(":core:model"))
    api(project(":core:security"))
    api(project(":plugin:api"))
    api(project(":plugin:manifest"))
    api(project(":plugin:installer"))
    api(project(":plugin:validator"))
    api(project(":plugin:resolver"))
    api(project(":plugin:protocol"))
    api(project(":runtime:api"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":plugin:installer")))
    testImplementation(testFixtures(project(":plugin:resolver")))
    testImplementation(project(":core:filesystem"))
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}
