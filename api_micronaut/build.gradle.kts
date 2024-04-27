plugins {
    id("bisq.java-library")
    alias(libs.plugins.micronaut)
}

repositories {
    mavenCentral()
}

micronaut {
    version.set("4.3.6")
}

dependencies {
    implementation(libs.bundles.micronaut)
    implementation(libs.jakarta.validation.api)

    // runtimeOnly("ch.qos.logback:logback-classic")
    // runtimeOnly("org.yaml:snakeyaml")
}
