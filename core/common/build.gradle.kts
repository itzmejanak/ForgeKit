plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit5.jupiter)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}
