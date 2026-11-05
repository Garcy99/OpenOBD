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
        // USB serial drivers (FTDI, CH340, PL2303, CP210x) for ELM327 USB cables, only published on JitPack
        maven("https://jitpack.io") { content { includeGroup("com.github.mik3y") } }
    }
}

rootProject.name = "OpenOBD"
include(":app")
