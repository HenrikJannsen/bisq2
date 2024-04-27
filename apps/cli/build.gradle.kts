plugins {
    id("bisq.java-library")
    application
}

application {
    mainClass.set("bisq.cli.CliApp")
}

dependencies {
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
