plugins {
  id("com.android.library")
  kotlin("android")
  id("com.vanniktech.maven.publish") version "0.30.0"
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

mavenPublishing {
  publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
  signAllPublications()

  coordinates("io.github.umutcansu", "traceflow-runtime", project.findProperty("VERSION_NAME") as? String ?: "1.0.0")

  pom {
    name.set("TraceFlow Runtime")
    description.set("Zero-code ASM bytecode tracing runtime for Android apps")
    url.set("https://github.com/umutcansu/TraceFlow")

    licenses {
      license {
        name.set("The Apache License, Version 2.0")
        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
      }
    }

    developers {
      developer {
        id.set("umutcansu")
        name.set("Umut Cansu")
        email.set("umutcansu@gmail.com")
      }
    }

    scm {
      connection.set("scm:git:git://github.com/umutcansu/TraceFlow.git")
      developerConnection.set("scm:git:ssh://github.com:umutcansu/TraceFlow.git")
      url.set("https://github.com/umutcansu/TraceFlow")
    }
  }
}
