pluginManagement {
    repositories {
        gradlePluginPortal()
    }
    includeBuild("build-logic")
}

plugins {
    id("bisq.gradle.toolchain_resolver.ToolchainResolverPlugin")
}

toolchainManagement {
    jvm {
        javaRepositories {
            repository("bisq_zulu") {
                resolverClass.set(bisq.gradle.toolchain_resolver.BisqToolchainResolver::class.java)
            }
        }
    }
}

rootProject.name = "bisq"

include("account")
include("android")
include("application")
include("bisq-easy")
include("bonded-roles")
include("chat")
include("common")
include("contract")
include("daemon")
include("identity")
include("internal")
include("i18n")
include("offer")
include("persistence")
include("platform")
include("presentation")
include("trade")
include("security")
include("settings")
include("support")
include("user")

includeBuild("apps")
includeBuild("network")
includeBuild("wallets")
