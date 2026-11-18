"""Build an independent consumer of the staged Maven artifacts and optionally run it on Android."""

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = ROOT / "build/maven"
SDK_VERSION = "0.2.1-dev"
PACK_VERSION = "0.2.0-dev"
PACK = "android-agent-backends-pi-codex"
BUILD = "./gradlew"
GRADLE = ["--console=plain"]
FIXTURE = "dev.androidagent.sample.fixture"


def verify_device(project, serial):
    adb = ["adb", "-s", serial]
    for artifact in ("apk/debug/sample-debug.apk", "apk/androidTest/debug/sample-debug-androidTest.apk"):
        subprocess.run(adb + ["install", "-r", str(project / "sample/build/outputs" / artifact)], check=True, timeout=60)
    for backend in ("pi", "codex"):
        result = subprocess.run(adb + ["shell", "am", "instrument", "-w", "-e", "backend", backend,
                                      FIXTURE + ".test/dev.androidagent.sample.SampleProbe"],
                                check=True, capture_output=True, text=True, timeout=60)
        if "Sample UI probe passed" not in result.stdout:
            raise RuntimeError(f"The independent consumer did not complete the {backend} task: {result.stdout}")
        print(result.stdout, end="\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="install a separate synthetic fixture on this device and run both backends")
    args = parser.parse_args()
    subprocess.run([
        BUILD, ":sdk:publishSdkPublicationToDevelopmentRepository",
        ":backends:publishBackendPackPublicationToDevelopmentRepository",
        ":speech:publishSpeechPublicationToDevelopmentRepository",
        ":client:publishClientPublicationToDevelopmentRepository",
        "-PagentBackends=pi,codex", "-PspeechHtp", *GRADLE,
    ], cwd=ROOT, check=True)
    with tempfile.TemporaryDirectory(prefix="android-agent-consumer-") as path:
        project = Path(path)
        for name in ("build.gradle.kts", "gradle.properties", "gradlew"):
            shutil.copy2(ROOT / name, project / name)
        for name in ("gradle", "sample/src"):
            shutil.copytree(ROOT / name, project / name)
        local_properties = ROOT / "local.properties"
        if local_properties.exists():
            shutil.copy2(local_properties, project / local_properties.name)

        settings = (ROOT / "settings.gradle.kts").read_text()
        settings = settings.replace('rootProject.name = "AndroidAgentSdk"', 'rootProject.name = "AgentConsumer"')
        settings = settings.replace('include(":sdk", ":backends", ":speech", ":client", ":sample")', 'include(":sample")')
        settings = settings.replace(
            'repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)\n    repositories {',
            '''repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url = uri(providers.environmentVariable("ANDROID_AGENT_FIXTURE_REPO").get())
            content { includeGroup("dev.androidagent") }
        }''',
        )
        (project / "settings.gradle.kts").write_text(settings)
        sample = (ROOT / "sample/build.gradle.kts").read_text()
        sample = sample.replace('implementation(project(":sdk"))',
                                f'implementation("dev.androidagent:android-agent-sdk:{SDK_VERSION}")')
        sample = sample.replace('if (providers.gradleProperty("agentBackends").isPresent) implementation(project(":backends"))',
                                f'implementation("dev.androidagent:{PACK}:{PACK_VERSION}")')
        sample = sample.replace('implementation(project(":speech"))',
                                'implementation("dev.androidagent:android-ai-speech-htp:0.1.2-dev")')
        sample = sample.replace('implementation(project(":client"))',
                                'implementation("dev.androidagent:android-ai-client:0.1.0-dev")')
        if "project(" in sample:
            raise RuntimeError("Consumer must resolve published Maven coordinates only.")
        (project / "sample/build.gradle.kts").write_text(sample)

        environment = {**os.environ, "ANDROID_AGENT_FIXTURE_REPO": str(REPOSITORY)}
        start = time.monotonic()
        subprocess.run([BUILD, ":sample:assembleDebug", *GRADLE], cwd=project, check=True, env=environment)
        duration = time.monotonic() - start
        apk = project / "sample/build/outputs/apk/debug/sample-debug.apk"
        artifact = ROOT / "build/consumer/consumer-debug.apk"
        artifact.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(apk, artifact)
        print(f"Independent consumer: {duration:.1f} s; APK {artifact}")
        if args.serial:
            subprocess.run([BUILD, ":sample:assembleDebug", ":sample:assembleDebugAndroidTest", "-PsampleFixture", *GRADLE],
                           cwd=project, check=True, env=environment)
            verify_device(project, args.serial)


if __name__ == "__main__":
    main()
