import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("plugin.serialization")
    `maven-publish`
}
android {
    namespace = "dev.androidagent.sdk"
    compileSdk = 35
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "dev.androidagent.sdk.AcpAndroidProbe"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("androidTest").java.srcDir("../sample/src/hostMcp/kotlin")
    packaging.jniLibs.useLegacyPackaging = true
    publishing.singleVariant("release") { withSourcesJar() }
}

abstract class BuildLauncher : DefaultTask() {
    @get:Inject abstract val exec: ExecOperations
    @get:InputFile abstract val script: RegularFileProperty
    @get:InputFile abstract val source: RegularFileProperty
    @get:Internal abstract val ndk: DirectoryProperty
    @get:Input abstract val ndkVersion: Property<String>
    @get:OutputDirectory abstract val output: DirectoryProperty

    @TaskAction
    fun compile() {
        exec.exec {
            commandLine(
                "sh", script.get().asFile, ndk.get().asFile,
                output.file("arm64-v8a/libandroid_agent_sandbox.so").get().asFile,
            )
        }
    }
}

androidComponents.onVariants { variant ->
    val launcher = tasks.register<BuildLauncher>("build${variant.name.replaceFirstChar(Char::uppercaseChar)}Launcher") {
        script.set(rootProject.layout.projectDirectory.file("native/build-launcher.sh"))
        source.set(rootProject.layout.projectDirectory.file("native/sandbox.c"))
        ndk.set(androidComponents.sdkComponents.ndkDirectory)
        ndkVersion.set(android.ndkVersion.orEmpty())
        output.set(layout.buildDirectory.dir("generated/launcher/${variant.name}"))
    }
    variant.sources.jniLibs?.addGeneratedSourceDirectory(launcher, BuildLauncher::output)
}
kotlin { jvmToolchain(17) }
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.agentclientprotocol:acp:0.34.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // kotlin-logging uses SLF4J on the JVM but the ACP artifact does not supply the API.
    runtimeOnly("org.slf4j:slf4j-api:2.0.17")
    if (providers.gradleProperty("agentBackends").isPresent) androidTestImplementation(project(":backends"))
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

publishing.repositories.maven {
    name = "Development"
    url = uri(rootProject.layout.buildDirectory.dir("maven"))
}

afterEvaluate {
    publishing.publications.register<MavenPublication>("sdk") {
        from(components["release"])
        groupId = "dev.androidagent"
        artifactId = "android-agent-sdk"
        version = "0.2.1-dev"
    }
}
