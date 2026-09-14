plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:security"))
    implementation(project(":plugin:api"))
    implementation(project(":plugin:manifest"))
    implementation(project(":plugin:installer"))
    implementation(project(":plugin:validator"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit5.jupiter)
    testImplementation(testFixtures(project(":plugin:installer")))
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

application {
    mainClass.set("com.forgekit.tools.forgevalidator.Cli")
}

distributions {
    main {
        contents {
            from(rootProject.file("LICENSE"))
            from(rootProject.file("third-party")) { into("third-party") }
        }
    }
}
