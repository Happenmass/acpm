plugins {
    id("com.android.application")
    kotlin("android")
}
android {
    namespace = "dev.androidagent.sample"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.androidagent.sample"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
        testInstrumentationRunner = "dev.androidagent.sample.SampleProbe"
    }
    buildTypes.getByName("debug") {
        versionNameSuffix = "-dev"
        if (providers.gradleProperty("sampleFixture").isPresent) applicationIdSuffix = ".fixture"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main").java.srcDir("src/hostMcp/kotlin")
    packaging.jniLibs.useLegacyPackaging = true
    defaultConfig.ndk.abiFilters += "arm64-v8a"
    androidResources.noCompress += listOf("bin", "onnx", "int8", "f32", "i32")
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":sdk"))
    implementation(project(":client"))
    if (providers.gradleProperty("agentBackends").isPresent) implementation(project(":backends"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

dependencies {
    implementation(project(":speech"))
    implementation(files(providers.gradleProperty("sherpaAar").getOrElse(
        System.getProperty("user.home") + "/.cache/codex/dependencies/sherpa-onnx/2.0.1/sherpa-onnx-2.0.1.aar"
    )))
}

if (providers.gradleProperty("speechHtp").isPresent) {
    val root = rootProject.layout.projectDirectory
    val output = layout.buildDirectory.dir("generated/speechModelAssets")
    val stageWeights by tasks.registering(Exec::class) {
        inputs.file(root.file("tools/stage-speech-models.py"))
        val profile = providers.gradleProperty("htpProfile").getOrElse("sm8850-o3")
        val modelLock = rootProject.file(providers.gradleProperty("htpModelLock").getOrElse("tools/qwen-htp/models.json"))
        inputs.property("htpProfile", profile)
        inputs.file(modelLock)
        inputs.file(root.file("tools/qwen-htp/model_lock.py"))
        inputs.dir(root.dir("tools/speech-models"))
        // Verify the shared cache every time the model APK is assembled.
        outputs.upToDateWhen { false }
        outputs.dir(output)
        commandLine("python3", root.file("tools/stage-speech-models.py").asFile, "--output", output.get().asFile, "--profile", profile, "--model-lock", modelLock)
    }
    android.sourceSets.getByName("main").assets.srcDir(output)
    tasks.named("preBuild").configure { dependsOn(stageWeights) }
}
