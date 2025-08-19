plugins {
    id("bisq.java-library")
    id("bisq.protobuf")
    id("bisq.java-integration-tests")
}

version = rootProject.version

dependencies {
    implementation("bisq:common")
    implementation("bisq:security")
    implementation("bisq:persistence")

    implementation(project(":network-identity"))
    implementation(project(":i2p"))
    implementation("tor:tor:$version")

    implementation(libs.bouncycastle)
    implementation(libs.failsafe)
    implementation(libs.typesafe.config)

    implementation(libs.apache.httpcomponents.httpclient)
    implementation(libs.chimp.jsocks)

    integrationTestImplementation(libs.mockito)

    implementation("io.netty:netty-transport:4.2.4.Final")
    implementation("io.netty:netty-buffer:4.2.4.Final")
    implementation("io.netty:netty-common:4.2.4.Final")
    implementation("io.netty:netty-handler:4.2.4.Final")
    implementation("io.netty:netty-handler:4.2.4.Final")
    implementation("io.netty:netty-handler-proxy:4.2.4.Final")

    // SOCKS protocol support
    implementation("io.netty:netty-codec-socks:4.2.4.Final")
    //implementation("io.grpc:grpc-netty-shaded:1.74.0")
}