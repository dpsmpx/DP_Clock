pluginManagement {
    repositories {
        // Google Maven — только для групп Android: ничего другого оттуда не приходит.
        google {
            content {
                includeGroupByRegex("""com\.android(\..*)?""")
                includeGroupByRegex("""androidx\..*""")
                includeGroupByRegex("""com\.google\.(android|testing)\..*""")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("""com\.android(\..*)?""")
                includeGroupByRegex("""androidx\..*""")
                includeGroupByRegex("""com\.google\.(android|testing)\..*""")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "DP_Clock"

include(":app")
