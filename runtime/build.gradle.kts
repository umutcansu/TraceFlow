plugins {
  id("com.android.library")
  kotlin("android")
  id("maven-publish")
}

android {
  namespace = "io.github.umutcansu.traceflow"
  compileSdk = 34
  defaultConfig { minSdk = 21 }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }
}

afterEvaluate {
  publishing {
    publications {
      create<MavenPublication>("release") {
        from(components["release"])
        groupId = "io.github.umutcansu"
        artifactId = "traceflow-runtime"
        version = project.findProperty("VERSION_NAME") as? String ?: "1.0.0"
      }
    }
  }
}
