plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    // the §21/§22 acceptance chain: package → import → install → job → protocol
    testImplementation(project(":core:model"))
    testImplementation(project(":core:security"))
    testImplementation(project(":plugin:api"))
    testImplementation(project(":plugin:manifest"))
    testImplementation(project(":plugin:installer"))
    testImplementation(project(":plugin:validator"))
    testImplementation(project(":plugin:manager"))
    testImplementation(project(":job:api"))
    testImplementation(project(":job:manager"))
    testImplementation(project(":job:persistence"))
    testImplementation(project(":runtime:api"))
    testImplementation(project(":tools:forge-builder"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(testFixtures(project(":plugin:installer")))
    testImplementation(testFixtures(project(":job:manager")))
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("FORGEKIT_EXAMPLES", rootProject.file("plugin-examples").absolutePath)
    systemProperty(
        "FORGEKIT_PYTHON",
        providers.environmentVariable("FORGEKIT_PYTHON").getOrElse("python3"),
    )
}
