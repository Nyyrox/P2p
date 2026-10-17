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
        // Google-hosted mirror of Maven Central, used if Central misses/hiccups on the CI runner.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
    }
}

rootProject.name = "PeerTalk"
include(":app")
