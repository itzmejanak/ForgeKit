plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
}

dependencies {
    api(project(":core:common"))
    api(project(":core:model"))
    api(project(":job:api"))
    api(project(":job:persistence"))
    api(project(":plugin:protocol"))
    api(project(":runtime:api"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testFixturesImplementation(project(":core:filesystem"))
    testFixturesImplementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}
