"""Build a pinned ARM64 Agent deployment bundle. Downloads happen only at build time."""

import argparse
import hashlib
import json
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
TOOLS = Path(__file__).resolve().parent
NODE_VERSION = "24.22.1"
NODE_BASE = f"https://github.com/fogtape/nodejs-mobile/releases/download/v{NODE_VERSION}-0"
NODE_ARCHIVE = f"nodejs-mobile-android-{NODE_VERSION}-0.zip"
NODE_SHA = "d6fd9f610fe5d81fede8f0d400c5a62723247725a19831835f15abf4f0604250"
NODE_LICENSE_SHA = "5888dbb9a1d2b18f2c3e6c5f6af1b39de658372b402a0577b002777f14c62ace"
CODEX_TAG = "rust-v0.165.0"
CODEX_ARCHIVE = "codex-aarch64-unknown-linux-musl.zst"
CODEX_ARCHIVE_SHA = "e5e5c2b300a3e2b0e8a1f887e928a3ecc63d825efd5b0c9ecd83cda5e5a060b8"
CODEX_SHA = "b32ece31af06440da980b733fb8fefdd1f193439595fda84096f1caaceec543f"
CODEX_LICENSE_SHA = "d17f227e4df5da1600391338865ce0f3055211760a36688f816941d58232d8dc"
CACHE = Path.home() / ".cache/codex/dependencies"
ADAPTERS = {
    "pi": ("@openma/pi-acp", "dist/bin.js"),
    "codex": ("@agentclientprotocol/codex-acp", "dist/index.js"),
}


def verify(path, sha):
    with path.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != sha:
            raise ValueError(f"{path.name} does not match the pinned SHA-256")


def fetch(path, url, sha):
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as stream:
            temporary = Path(stream.name)
        try:
            subprocess.run(["curl", "-fsSL", "--retry", "2", "--output", temporary, url], check=True)
            verify(temporary, sha)
            temporary.replace(path)
        finally:
            temporary.unlink(missing_ok=True)
    verify(path, sha)
    return path


def build_node(ndk, jni, work, notices):
    cache = CACHE / "nodejs-mobile" / NODE_VERSION
    archive = fetch(cache / "full.zip", f"{NODE_BASE}/{NODE_ARCHIVE}", NODE_SHA)
    license_file = fetch(cache / "NODE-LICENSE.txt", f"https://raw.githubusercontent.com/nodejs/node/v{NODE_VERSION}/LICENSE", NODE_LICENSE_SHA)
    shutil.copyfile(license_file, notices / "Node-LICENSE.txt")
    with zipfile.ZipFile(archive) as package:
        for name in package.namelist():
            if name.startswith("include/node/") or name == "bin/arm64-v8a/libnode.so":
                package.extract(name, work)
    platform_tag = {"Darwin": "darwin-x86_64", "Linux": "linux-x86_64"}[platform.system()]
    toolchain = ndk / "toolchains/llvm/prebuilt" / platform_tag
    node = work / "bin/arm64-v8a/libnode.so"
    subprocess.run([
        toolchain / "bin/aarch64-linux-android26-clang++", "-std=c++20", "-O2",
        "-Wl,-rpath,$ORIGIN", "-Wl,-z,max-page-size=16384",
        "-I", work / "include/node", ROOT / "native/node-main.cc", node,
        "-o", jni / "libandroid_agent_node.so",
    ], check=True)
    shutil.copyfile(node, jni / "libnode.so")
    shutil.copyfile(toolchain / "sysroot/usr/lib/aarch64-linux-android/libc++_shared.so", jni / "libc++_shared.so")


def stage_codex(jni, notices):
    cache = CACHE / "codex-cli" / CODEX_TAG
    license_file = fetch(cache / "LICENSE.txt", f"https://raw.githubusercontent.com/openai/codex/{CODEX_TAG}/LICENSE", CODEX_LICENSE_SHA)
    shutil.copyfile(license_file, notices / "Codex-LICENSE.txt")
    cached_executable = cache / "codex-aarch64-unknown-linux-musl"
    if not cached_executable.exists():
        archive = fetch(cache / CODEX_ARCHIVE, f"https://github.com/openai/codex/releases/download/{CODEX_TAG}/{CODEX_ARCHIVE}", CODEX_ARCHIVE_SHA)
        with tempfile.NamedTemporaryFile(dir=cache, delete=False) as stream:
            temporary = Path(stream.name)
        try:
            subprocess.run(["zstd", "-q", "-d", "-f", archive, "-o", temporary], check=True)
            verify(temporary, CODEX_SHA)
            temporary.replace(cached_executable)
        finally:
            temporary.unlink(missing_ok=True)
    verify(cached_executable, CODEX_SHA)
    shutil.copyfile(cached_executable, jni / "libandroid_agent_codex.so")


def dependency_paths(lock, roots):
    """Keep the selected adapters and each installed production dependency in Node's lookup order."""
    packages = lock["packages"]
    selected = set()
    pending = [(Path("."), name) for name in roots]
    while pending:
        owner, dependency = pending.pop()
        for parent in (owner, *owner.parents):
            path = (parent / "node_modules" / dependency).as_posix()
            if path in packages and not packages[path].get("optional"):
                break
        else:
            raise ValueError(f"Missing production dependency {dependency} of {owner}")
        if path in selected:
            continue
        selected.add(path)
        meta = packages[path]
        pending.extend((Path(path), name) for name in meta.get("dependencies", {})
                       if name not in meta.get("optionalDependencies", {}))
        pending.extend((Path(path), name) for name in meta.get("peerDependencies", {})
                       if not meta.get("peerDependenciesMeta", {}).get(name, {}).get("optional"))
    return sorted(selected)


def stage_javascript(work, bundle, selected):
    for source in ("package.json", "package-lock.json"):
        shutil.copyfile(TOOLS / source, work / source)
    subprocess.run(["npm", "ci", "--ignore-scripts", "--omit=optional", "--no-audit", "--no-fund"],
                   cwd=work, check=True)
    lock = json.loads((work / "package-lock.json").read_text())
    roots = ["@modelcontextprotocol/sdk"] + [ADAPTERS[name][0] for name in selected]
    retained = dependency_paths(lock, roots)
    manifest = {
        "runtime": NODE_VERSION,
        "adapters": {name: {"entry": f"node_modules/{ADAPTERS[name][0]}/{ADAPTERS[name][1]}",
                            "version": lock["packages"][f"node_modules/{ADAPTERS[name][0]}"]["version"]}
                     for name in sorted(selected)},
    }
    with zipfile.ZipFile(bundle, "w", zipfile.ZIP_DEFLATED, compresslevel=5) as package:
        for dependency in retained:
            root = work / dependency
            for path in sorted(root.rglob("*")):
                relative = path.relative_to(root)
                if "node_modules" in relative.parts or not path.is_file() or path.is_symlink():
                    continue
                # Runtime sources, resource files, manifests and license texts all stay in place.
                package.write(path, path.relative_to(work))
    with bundle.open("rb") as stream:
        manifest["sha256"] = hashlib.file_digest(stream, "sha256").hexdigest()
    bundle.with_name("versions.json").write_text(json.dumps(manifest, indent=2) + "\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--jni", type=Path, required=True)
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--backends", required=True)
    args = parser.parse_args()
    selected = set(args.backends.split(","))
    if not selected or not selected <= ADAPTERS.keys():
        parser.error("Choose pi, codex or pi,codex.")

    with tempfile.TemporaryDirectory(prefix="android-agent-build-") as temp:
        work = Path(temp)
        jni = work / "output-jni/arm64-v8a"
        assets = work / "output-assets/android-agent"
        notices = assets / "licenses"
        jni.mkdir(parents=True)
        notices.mkdir(parents=True)
        build_node(args.ndk, jni, work, notices)
        if "codex" in selected:
            stage_codex(jni, notices)
        stage_javascript(work, assets / "backend.zip", selected)
        for output, source in ((args.jni, jni.parent), (args.assets, assets.parent)):
            if output.exists():
                shutil.rmtree(output)
            shutil.copytree(source, output)


if __name__ == "__main__":
    main()
