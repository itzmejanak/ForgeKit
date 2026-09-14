plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:common"))
    api(project(":job:api"))

    implementation(libs.sqlite.jdbc)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit5.jupiter)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}
