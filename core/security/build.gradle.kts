plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":core:common"))
    api(project(":core:model"))
    api(project(":core:logging"))
    api(libs.bouncycastle)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit5.jupiter)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}
