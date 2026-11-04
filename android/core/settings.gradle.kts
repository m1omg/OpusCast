// The platform-independent part of OpusCast for Android (DLNA control, the file server, subtitles).
// It is its own small build so its tests run on any JDK without the Android SDK:
//   ./gradlew -p core test        (from the android/ folder)
// The app compiles these same sources in (see app/build.gradle.kts).
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
}
rootProject.name = "opuscast-core"
