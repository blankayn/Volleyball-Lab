pluginManagement {
    repositories {
        // Content filters stop Gradle probing Google's repository for artifacts
        // that only exist on Maven Central, and vice versa. Purely a resolution
        // speed matter, but it is noticeable on a cold build.
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
    // Modules may not declare their own repositories. Everything resolves from
    // the two declared here, so a fresh clone cannot silently pull from a repo
    // that only exists on one developer's machine.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "VolleyballLab"
include(":app")
