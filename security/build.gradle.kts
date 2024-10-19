plugins {
    java
    id("bisq.java-conventions")
    //id("bisq.java-library")
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
            artifactId = "security"
        }
    }
    repositories {
        mavenLocal()
    }
}

dependencies {
    implementation(project(":common"))
    implementation(project(":persistence"))

    implementation(libs.bouncycastle)
    implementation(libs.bouncycastle.pg)
    implementation(libs.typesafe.config)

    testImplementation(libs.apache.commons.lang)
}
