pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ForgeKit2"

include(":app")

include(":core:common")
include(":core:model")
include(":core:database")
include(":core:logging")
include(":core:security")
include(":core:filesystem")

include(":platform:android")
include(":platform:notifications")
include(":platform:storage")
include(":platform:permissions")
include(":platform:lifecycle")

include(":runtime:api")
include(":runtime:bridge")
include(":runtime:bootstrap")
include(":runtime:termux")

include(":plugin:api")
include(":plugin:manifest")
include(":plugin:installer")
include(":plugin:validator")
include(":plugin:resolver")
include(":plugin:protocol")
include(":plugin:manager")
include(":plugin:registry")

include(":job:api")
include(":job:manager")
include(":job:persistence")

include(":ui:design")
include(":ui:navigation")
include(":ui:home")
include(":ui:plugins")
include(":ui:jobs")
include(":ui:terminal")
include(":ui:settings")

include(":termux:embedded")

include(":tests:integration")

include(":tools:forge-validator")
include(":tools:forge-builder")
include(":tools:forge-test")

// Physical directory mapping (STRUCTURE.md §4): plugins/ -> :plugin:*, jobs/ -> :job:*
project(":plugin:api").projectDir = file("plugins/api")
project(":plugin:manifest").projectDir = file("plugins/manifest")
project(":plugin:installer").projectDir = file("plugins/installer")
project(":plugin:validator").projectDir = file("plugins/validator")
project(":plugin:resolver").projectDir = file("plugins/resolver")
project(":plugin:protocol").projectDir = file("plugins/protocol")
project(":plugin:manager").projectDir = file("plugins/manager")
project(":plugin:registry").projectDir = file("plugins/registry")

project(":job:api").projectDir = file("jobs/api")
project(":job:manager").projectDir = file("jobs/manager")
project(":job:persistence").projectDir = file("jobs/persistence")
