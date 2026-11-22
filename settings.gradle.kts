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
        // NewPipeExtractor is published on JitPack, which builds anyone's GitHub project on
        // request. So it's used for NewPipe's own packages only, and those come only from
        // there: no other dependency can be swapped for a look-alike published on JitPack.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroupByRegex("""com\.github\.TeamNewPipe(\..+)?""") }
        }
    }
}

rootProject.name = "Antenna"
include(":app")
