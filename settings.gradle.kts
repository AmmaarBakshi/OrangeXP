pluginManagement {
    includeBuild("build-logic")
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

rootProject.name = "OrangeXP"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")

include(":core:common")
include(":core:engine")
include(":core:database")
include(":core:sensing")
include(":core:data")
include(":core:work")
include(":core:designsystem")
include(":core:ui")

include(":feature:today")
include(":feature:history")
include(":feature:academics")
include(":feature:competitions")
include(":feature:settings")
include(":feature:widgets")
