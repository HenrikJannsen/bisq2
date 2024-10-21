plugins {
    id("bisq.java-library")
    id("maven-publish")
}

group = "bisq"
version = project.version

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "application"
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
    implementation(project(":settings"))
    implementation(project(":user"))
    implementation(project(":chat"))
    implementation(project(":support"))
    implementation(project(":bonded-roles"))
    implementation(project(":offer"))
    implementation(project(":trade"))

    implementation("network:network")

    implementation(libs.typesafe.config)
}
