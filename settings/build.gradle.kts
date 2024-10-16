plugins {
    id("bisq.java-library")
    id("bisq.protobuf")
    id("maven-publish")
}

group = "bisq"
version = project.version

tasks.withType<Jar> {
    // Include .proto files from src/main/proto into the JAR
    from("src/main/proto") {
        include("**/*.proto")
        into("proto") // Optional: specify a directory inside the JAR where .proto files will be placed
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "settings"
        }
    }
    repositories {
        mavenLocal()
    }
}

dependencies {
    implementation(project(":persistence"))
    implementation(project(":i18n"))

    implementation("network:network")
}
