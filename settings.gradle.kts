pluginManagement {
    repositories {
        google()
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

rootProject.name = "Volta"

include(":app")
include(":core:model", ":core:planner", ":core:ismart", ":core:maps", ":core:domain")
include(":core:data", ":core:designsystem", ":core:testing")
include(":feature:onboarding")
