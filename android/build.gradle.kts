plugins {
    java
    id("bisq.java-conventions")
    //id("bisq.java-library")
    id("maven-publish")
}

group = "bisq"
version = project.version

tasks.withType<Jar> {
    // Include .proto files from src/main/proto into the JAR
    from("src/main/resources") {
        include("android.conf")
        into("resources") // Optional: specify a directory inside the JAR where .proto files will be placed
    }
}
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
