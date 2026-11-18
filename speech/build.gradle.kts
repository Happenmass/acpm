plugins {
    id("com.android.library")
    kotlin("android")
    `maven-publish`
}

android {
    namespace = "dev.androidagent.speech"
    compileSdk = 35
    ndkVersion = "28.2.13676358"
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing.singleVariant("release") { withSourcesJar() }
}
kotlin { jvmToolchain(17) }

val sherpaAar = providers.gradleProperty("sherpaAar").orElse(
    System.getProperty("user.home") + "/.cache/codex/dependencies/sherpa-onnx/2.0.1/sherpa-onnx-2.0.1.aar"
)
val sherpaClasses by tasks.registering(Copy::class) {
    from({ zipTree(sherpaAar.get()) }) { include("classes.jar") }
    into(layout.buildDirectory.dir("sherpa"))
}
val sources = rootProject.layout.projectDirectory
val speechNative by tasks.registering(Exec::class) {
    val output = layout.buildDirectory.dir("generated/speechJni")
    inputs.file(sources.file("tools/build-speech-vad.py"))
    inputs.file(sources.file("speech/src/main/cpp/vad.cc"))
    outputs.dir(output)
    commandLine("python3", sources.file("tools/build-speech-vad.py").asFile,
        androidComponents.sdkComponents.ndkDirectory.get().asFile, output.get().asFile)
}
android.sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/speechJni"))
tasks.named("preBuild").configure { dependsOn(speechNative) }
dependencies {
    compileOnly(files(layout.buildDirectory.file("sherpa/classes.jar")).builtBy(sherpaClasses))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
publishing.repositories.maven {
    name = "Development"
    url = uri(rootProject.layout.buildDirectory.dir("maven"))
}
afterEvaluate {
    publishing.publications.register<MavenPublication>("speech") {
        from(components["release"])
        groupId = "dev.androidagent"
        artifactId = if (providers.gradleProperty("speechHtp").isPresent) "android-ai-speech-htp" else "android-ai-speech"
        version = "0.1.2-dev"
    }
}

if (providers.gradleProperty("speechHtp").isPresent) {
    val prepareHtp by tasks.registering(Exec::class) {
        val jni = layout.buildDirectory.dir("generated/htpJni")
        val assets = layout.buildDirectory.dir("generated/htpAssets")
        val profile = providers.gradleProperty("htpProfile").getOrElse("sm8850-o3")
        val modelLock = rootProject.file(providers.gradleProperty("htpModelLock").getOrElse("tools/qwen-htp/models.json"))
        inputs.property("htpProfile", profile)
        inputs.file(modelLock)
        val runtimeLock = rootProject.file(providers.gradleProperty("htpRuntimeLock").getOrElse("tools/qwen-htp/libraries.json"))
        inputs.file(runtimeLock)
        inputs.dir(sources.dir("tools/qwen-htp"))
        outputs.dirs(jni, assets)
        commandLine("python3", sources.file("tools/qwen-htp/build.py").asFile,
            "--jni", jni.get().asFile, "--assets", assets.get().asFile,
            "--profile", profile, "--model-lock", modelLock, "--runtime-lock", runtimeLock)
    }
    android.sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/htpJni"))
    android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/htpAssets"))
    tasks.named("preBuild").configure { dependsOn(prepareHtp) }
}
