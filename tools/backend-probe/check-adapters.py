"""Check the pinned upstream adapters on the development host without model credentials."""

import json
import os
from pathlib import Path
import select
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent / "node_modules"
ADAPTERS = {
    "Pi": ROOT / "@openma/pi-acp/dist/bin.js",
    "Codex": ROOT / "@agentclientprotocol/codex-acp/dist/index.js",
}


def check(name, entry, codex):
    with tempfile.TemporaryDirectory(prefix="android-acp-probe-") as workspace:
        env = {
            "PATH": os.environ["PATH"],
            "HOME": workspace,
            "CODEX_HOME": workspace,
            "CODEX_PATH": codex,
            "NO_BROWSER": "1",
        }
        with subprocess.Popen(
            ["node", entry], cwd=workspace, env=env,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE,
        ) as peer:
            try:
                request = {
                    "jsonrpc": "2.0",
                    "id": 1,
                    "method": "initialize",
                    "params": {"protocolVersion": 1, "clientCapabilities": {}},
                }
                peer.stdin.write((json.dumps(request) + "\n").encode())
                peer.stdin.flush()
                if not select.select([peer.stdout], [], [], 15)[0]:
                    raise RuntimeError(f"{name} did not answer initialize.")
                reply = peer.stdout.readline()
                if not reply:
                    raise RuntimeError(f"{name} exited before answering initialize.")
                response = json.loads(reply)
                info = response["result"]
                assert response["id"] == 1 and info["protocolVersion"] == 1, response
                print(f"{name}: ACP v1, adapter {info['agentInfo']['version']}, "
                      f"session/load {info['agentCapabilities']['loadSession']}")
            finally:
                peer.stdin.close()
                try:
                    peer.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    peer.kill()


if __name__ == "__main__":
    exe = shutil.which("codex")
    if not exe:
        raise SystemExit("Install Codex CLI on the development host before running this probe.")
    for backend, adapter in ADAPTERS.items():
        check(backend, adapter, exe)
