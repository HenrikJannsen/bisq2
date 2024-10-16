plugins {
    java
    id("bisq.java-conventions")
}

dependencies {
    implementation(libs.annotations)
    implementation(project(":common"))
}
