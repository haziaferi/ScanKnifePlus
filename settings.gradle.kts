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
        // Tesseract4Android (the owner's fork) is published only on JitPack; nothing else may resolve from it.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.haziaferi.Tesseract4Android") }
        }
    }
}

rootProject.name = "ScanKnifePlus"
include(":app")
include(":scanner-core")
