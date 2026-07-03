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

rootProject.name = "Flint"

include(":app")

// core — model + shared utilities + theme tokens + persistence
include(":core:core-model")
include(":core:core-common")
include(":core:core-data")
include(":core:core-datastore")               // Jetpack DataStore: observable user preferences

// blocking — the enforcement engine (UI-independent, headless-capable)
include(":blocking:blocking-engine")          // pure-Kotlin decision core (unit-tested)
include(":blocking:blocking-accessibility")   // path A: AccessibilityService + URL reads + a11y overlay
include(":blocking:blocking-usagestats")      // path B: UsageStatsManager poll + daily budgets (fallback)
include(":blocking:blocking-overlay")         // path B enforcement: overlay + full-screen block Activity
include(":blocking:blocking-resilience")      // OEM/battery survival: boot re-arm, exit reasons, health

// permissions — special-access plumbing, decoupled from UI
include(":permissions:permissions-special")

// feature — Compose UI; feature-onboarding owns the PROMINENT DISCLOSURE consent screen
include(":feature:feature-onboarding")
include(":feature:feature-blocklist")         // pick apps/sites, schedules, limits, break level
include(":feature:feature-stats")             // screen-time reports from UsageStatsManager
include(":feature:feature-blockscreen")       // shared branded "blocked" lock UI
include(":feature:feature-settings")          // battery exemption, OEM auto-start, re-enable prompts
