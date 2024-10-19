import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("bisq.java-library")
    id("bisq.protobuf")
    id("maven-publish")
    alias(libs.plugins.shadow)
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

tasks {
    named<Jar>("jar") {
        manifest {
            // doFirst {
            //     println("project version is ${project.version}");
            // }
            attributes(
                mapOf(
                    Pair("Implementation-Title", project.name),
                    Pair("Implementation-Version", project.version),
                    Pair("Main-Class", "bisq.desktop_app.DesktopApp")
                )
            )
        }
    }

    named<ShadowJar>("shadowJar") {
       // val platformName = getPlatform().platformName
      //  archiveClassifier.set(platformName + "-all")
        archiveClassifier.set("")
    }
}
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "persistence"
        }
    }
    repositories {
        mavenLocal()
    }
}

dependencies {
    implementation(project(":common"))
}
