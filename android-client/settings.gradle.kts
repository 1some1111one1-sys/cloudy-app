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
        maven { url = uri("https://jitpack.io") }   // FreeReflection, used by the virtualization engine
    }
}

rootProject.name = "CloudDroid"
include(":app")
// Virtualization engine (vendored from NewBlackbox, Apache-2.0): see engine/README.md
include(":black-reflection", ":compiler", ":Bcore")
project(":black-reflection").projectDir = file("engine/black-reflection")
project(":compiler").projectDir = file("engine/compiler")
project(":Bcore").projectDir = file("engine/Bcore")
