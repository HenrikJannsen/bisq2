plugins {
    id ("bisq.java-library")
    id ("bisq.protobuf")
    id("maven-publish")
}

group = "bisq"
version = "2.1.1"

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "network-identity"
        }
    }
    repositories {
        mavenLocal()
    }
}

dependencies {
    implementation(project(":network-common"))
    implementation("bisq:security")

    implementation(libs.bouncycastle)
}
