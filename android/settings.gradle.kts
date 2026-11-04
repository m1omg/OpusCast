// OpusCast for Android. Build the app with ./gradlew assembleRelease (needs the Android SDK).
// The platform-independent part lives in core/ - a separate build whose tests run on a plain JDK:
//   ./gradlew -p core test
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
rootProject.name = "OpusCast"
include(":app")
