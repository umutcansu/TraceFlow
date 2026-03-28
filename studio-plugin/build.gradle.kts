plugins {
  id("java")
  id("org.jetbrains.kotlin.jvm") version "2.1.0"
  id("org.jetbrains.intellij.platform") version "2.7.1"
}

group = "io.github.umutcansu.TraceFlow"
version = project.findProperty("VERSION_NAME") as? String ?: "1.0.0"

repositories {
  mavenCentral()
  intellijPlatform {
    defaultRepositories()
  }
}

dependencies {
  intellijPlatform {
    androidStudio("2025.2.1.6")
    testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    bundledPlugin("org.jetbrains.android")
  }
  implementation("com.google.code.gson:gson:2.11.0")
}

intellijPlatform {
  pluginConfiguration {
    ideaVersion {
      sinceBuild = "241"
    }
  }
  publishing {
    token.set(providers.gradleProperty("ORG_JETBRAINS_INTELLIJ_PLATFORM_PUBLISH_TOKEN"))
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
  }
}
