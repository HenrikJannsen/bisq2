pluginManagement {
    repositories {
        gradlePluginPortal()
    }
    includeBuild("../build-logic")
}

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

includeBuild("..")
includeBuild("desktop")
includeBuild("oracle-node")

include("daemon")
include("rest-api-app")
include("seed-node-app")

rootProject.name = "apps"
