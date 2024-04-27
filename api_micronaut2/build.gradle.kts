plugins {
    id("io.micronaut.library") version "4.3.6"
}

repositories {
    mavenCentral()
}

micronaut {
    version.set("4.4.0")
}

dependencies {
    annotationProcessor("io.micronaut.openapi:micronaut-openapi")
    annotationProcessor("io.micronaut.serde:micronaut-serde-processor")
    annotationProcessor("io.micronaut.validation:micronaut-validation-processor")
    annotationProcessor("io.micronaut:micronaut-http-validation")

    implementation("io.micronaut.openapi:micronaut-openapi-annotations")
    implementation("io.micronaut.serde:micronaut-serde-jackson")
    implementation("io.micronaut.validation:micronaut-validation")
    implementation("io.micronaut:micronaut-http-client")
    implementation("io.micronaut:micronaut-http-server-netty")
    implementation("io.micronaut:micronaut-inject")
    implementation("io.micronaut:micronaut-jackson-databind")
    implementation("io.micronaut:micronaut-runtime")
    implementation("jakarta.validation:jakarta.validation-api")

    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly("org.yaml:snakeyaml")
}