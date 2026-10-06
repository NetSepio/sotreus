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

rootProject.name = "sotreus"

include(":app")
include(":core:model")
include(":core:navigation")
include(":core:crypto")
include(":core:data")
include(":core:testing")
include(":intelligence:core")
include(":sensing:android")
include(":social:presence")
include(":integration:solana")
include(":core:designsystem")
include(":core:ui")
include(":core:database")
include(":intelligence:fieldwatch")
include(":feature:onboarding")
include(":feature:now")
include(":feature:entity")
include(":feature:attention")
include(":feature:place")
include(":feature:friends")
include(":feature:proofs")
include(":feature:history")
include(":feature:journey")
include(":feature:settings")
