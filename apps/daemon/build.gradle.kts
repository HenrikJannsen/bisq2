plugins {
    id("bisq.java-library")
    application
}

application {
    mainClass.set("bisq.daemon.Daemon")
}

dependencies {
    implementation("bisq:persistence")
    implementation("bisq:i18n")
    implementation("bisq:security")
    implementation("bisq:identity")
    implementation("bisq:account")
    implementation("bisq:offer")
    implementation("bisq:contract")
    implementation("bisq:trade")
    implementation("bisq:bonded-roles")
    implementation("bisq:settings")
    implementation("bisq:user")
    implementation("bisq:chat")
    implementation("bisq:support")
    implementation("bisq:presentation")
    implementation("bisq:bisq-easy")
    implementation("bisq:application")
    implementation("bisq:core")
    implementation("bisq:api")

    implementation("network:network")
    implementation("wallets:electrum")
    implementation("wallets:bitcoind")

    implementation(libs.bundles.glassfish.jersey)
    implementation(libs.bundles.jackson)
    implementation(libs.typesafe.config)
}

tasks {
    distZip {
        enabled = false
    }

    distTar {
        enabled = false
    }
}
