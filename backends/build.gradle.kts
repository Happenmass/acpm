import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    id("com.android.library")
    `maven-publish`
}

android {
    namespace = "dev.androidagent.backends"
    compileSdk = 35
    ndkVersion = "28.2.13676358"
    defaultConfig { minSdk = 26 }
    publishing.singleVariant("release")
}

val backendSelection = providers.gradleProperty("agentBackends").orElse("pi,codex")

abstract class PackageBackends : DefaultTask() {
    @get:Inject abstract val exec: ExecOperations
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection
    @get:Internal abstract val script: RegularFileProperty
    @get:Internal abstract val ndk: DirectoryProperty
    @get:Input abstract val ndkVersion: Property<String>
    @get:Input abstract val selection: Property<String>
    @get:OutputDirectory abstract val jni: DirectoryProperty
    @get:OutputDirectory abstract val assets: DirectoryProperty

    @TaskAction
    fun build() {
        exec.exec {
            commandLine(
                "python3", script.get().asFile, "--ndk", ndk.get().asFile,
                "--jni", jni.get().asFile, "--assets", assets.get().asFile,
                "--backends", selection.get(),
            )
        }
    }
}

androidComponents.onVariants { variant ->
    val prepare = tasks.register<PackageBackends>("package${variant.name.replaceFirstChar(Char::uppercaseChar)}Backends") {
        val root = rootProject.layout.projectDirectory
        script.set(root.file("tools/backend-probe/package-android.py"))
        sources.from(script, root.file("native/node-main.cc"),
            root.file("tools/backend-probe/package.json"), root.file("tools/backend-probe/package-lock.json"))
        ndk.set(androidComponents.sdkComponents.ndkDirectory)
        ndkVersion.set(android.ndkVersion.orEmpty())
        selection.set(backendSelection)
        jni.set(layout.buildDirectory.dir("generated/backends/${variant.name}/jni"))
        assets.set(layout.buildDirectory.dir("generated/backends/${variant.name}/assets"))
    }
    variant.sources.jniLibs?.addGeneratedSourceDirectory(prepare, PackageBackends::jni)
    variant.sources.assets?.addGeneratedSourceDirectory(prepare, PackageBackends::assets)
}

publishing.repositories.maven {
    name = "Development"
    url = uri(rootProject.layout.buildDirectory.dir("maven"))
}

afterEvaluate {
    publishing.publications.register<MavenPublication>("backendPack") {
        from(components["release"])
        groupId = "dev.androidagent"
        val suffix = backendSelection.get().split(',').toSet().sortedDescending().joinToString("-")
        artifactId = "android-agent-backends-$suffix"
        version = "0.2.0-dev"
    }
}
