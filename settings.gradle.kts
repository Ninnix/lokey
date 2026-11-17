pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "Lokey"
include(":app")
include(":typing-benchmark")
project(":typing-benchmark").projectDir=file("tools/typing-benchmark")
