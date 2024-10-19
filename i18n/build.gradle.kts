plugins {
    java
    id("bisq.java-conventions")
    //id("bisq.java-library")
    id("maven-publish")
}

group = "bisq"
version = project.version

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "i18n"
        }
    }
    repositories {
        mavenLocal()
    }
}

dependencies {
    implementation(project(":common"))
}
