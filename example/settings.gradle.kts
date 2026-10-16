pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "RiviumAuthExample"

include(":app")

// Rivium Auth SDK (local module)
include(":rivium-auth")
project(":rivium-auth").projectDir = file("../rivium-auth")
