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
rootProject.name = "DropdownPicker"
include(":app")
include(":inspector")
include(":checker")
include(":admin")
include(":filler")
include(":engineapi")
include(":engine")
