plugins {
  `java-gradle-plugin`
  kotlin("jvm") version "2.1.0"
  id("com.gradle.plugin-publish") version "1.3.1"
}

group = "io.github.umutcansu"
version = project.findProperty("VERSION_NAME") as? String ?: "1.0.0"

repositories {
  mavenCentral()
  google()
}

dependencies {
  compileOnly("com.android.tools.build:gradle:8.2.2")
  implementation("org.ow2.asm:asm:9.7.1")
  implementation("org.ow2.asm:asm-commons:9.7.1")
}

gradlePlugin {
  website.set("https://github.com/umutcansu/TraceFlow")
  vcsUrl.set("https://github.com/umutcansu/TraceFlow")
  plugins {
    create("traceflow") {
      id = "io.github.umutcansu.traceflow"
      implementationClass = "io.github.umutcansu.traceflow.plugin.TracingPlugin"
      displayName = "TraceFlow"
      description = "Zero-code ASM bytecode tracing for Android apps"
      tags.set(listOf("android", "tracing", "debugging", "asm", "bytecode"))
    }
  }
}

kotlin {
  jvmToolchain(17)
}
