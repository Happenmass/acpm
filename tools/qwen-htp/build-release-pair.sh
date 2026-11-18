#!/usr/bin/env bash
# Keep both custom APKs under distinct release names even though they share one application ID.
set -euo pipefail
out=${1:?Usage: build-release-pair.sh OUTPUT_DIRECTORY [Gradle options]}
shift
mkdir -p "$out"
out=$(cd "$out" && pwd)
root=$(cd "$(dirname "$0")/../.." && pwd)
cd "$root"
for profile in o2 sm8850-o3; do
    ./gradlew :sample:assembleRelease "$@" -PagentBackends=pi,codex -PspeechHtp -PhtpProfile="$profile"
    apk=sample/build/outputs/apk/release/sample-release-unsigned.apk
    python3 - "$apk" "$profile" <<'PYTHON'
import json, sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as apk:
    manifest = json.loads(apk.read("assets/qwen-htp-manifest.json"))
    if manifest["profile"] != sys.argv[2]:
        raise SystemExit("Packaged model profile mismatch")
PYTHON
    cp "$apk" "$out/android-agent-sdk-$profile-unsigned.apk"
done
