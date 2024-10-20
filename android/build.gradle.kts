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
            artifactId = "android"
        }
    }
    repositories {
        mavenLocal()
    }
}

dependencies {
    implementation(project(":common"))
    implementation(project(":i18n"))
    implementation(project(":persistence"))
    implementation(project(":security"))

    implementation(libs.typesafe.config)
    implementation(libs.bouncycastle.pg)
}
