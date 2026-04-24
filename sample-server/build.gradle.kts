plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core:3.1.1")
    implementation("io.ktor:ktor-server-netty:3.1.1")
    implementation("io.ktor:ktor-server-content-negotiation:3.1.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.1")
    implementation("io.ktor:ktor-server-cors:3.1.1")
    implementation("io.ktor:ktor-server-compression:3.1.1")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    implementation("org.jetbrains.exposed:exposed-core:0.57.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.57.0")
    implementation("org.jetbrains.exposed:exposed-json:0.57.0")
    implementation("org.xerial:sqlite-jdbc:3.47.2.0")
}

application {
    mainClass.set("io.github.umutcansu.traceflow.server.MainKt")
}
