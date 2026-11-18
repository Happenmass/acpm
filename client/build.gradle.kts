plugins {
    id("com.android.library")
    kotlin("android")
    `maven-publish`
}
android {
    namespace = "dev.androidagent.client"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing.singleVariant("release") { withSourcesJar() }
}
kotlin { jvmToolchain(17) }
dependencies { api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2") }
publishing.repositories.maven {
    name = "Development"
    url = uri(rootProject.layout.buildDirectory.dir("maven"))
}
afterEvaluate {
    publishing.publications.register<MavenPublication>("client") {
        from(components["release"])
        groupId = "dev.androidagent"
        artifactId = "android-ai-client"
        version = "0.1.0-dev"
    }
}
