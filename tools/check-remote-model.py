"""Verify both Android backends against a caller-provided host-side model endpoint."""

import argparse
import shlex
import subprocess

PROBES = {
    "sdk": ("dev.androidagent.sdk.test/dev.androidagent.sdk.AcpAndroidProbe", "Remote model probe passed"),
    "sample": ("dev.androidagent.sample.fixture.test/dev.androidagent.sample.SampleProbe", "Sample remote model probe passed"),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial")
    parser.add_argument("host_port", type=int)
    parser.add_argument("--pi-model", required=True)
    parser.add_argument("--codex-model", required=True)
    parser.add_argument("--probe", choices=PROBES, required=True)
    parser.add_argument("--emulator", action="store_true", help="connect to the emulator host alias instead of using USB reverse")
    args = parser.parse_args()
    if not 1 <= args.host_port <= 65535:
        parser.error("host_port must be between 1 and 65535")

    adb = ["adb", "-s", args.serial]
    if args.emulator and not args.serial.startswith("emulator-"):
        parser.error("--emulator requires an emulator serial")
    mapping = None
    if not args.emulator:
        mapping = subprocess.check_output(adb + ["reverse", "--no-rebind", "tcp:0", f"tcp:{args.host_port}"],
                                          text=True, timeout=5).strip()
        if not mapping.isdecimal():
            raise RuntimeError("ADB did not return an allocated reverse port")
    host = "10.0.2.2" if args.emulator else "127.0.0.1"
    port = mapping or str(args.host_port)
    runner, success = PROBES[args.probe]
    failed = []
    try:
        for backend, model in (("pi", args.pi_model), ("codex", args.codex_model)):
            print(f"Running {args.probe} with {backend}", flush=True)
            command = ["am", "instrument", "-w", "-e", "stage", "remote", "-e", "backend", backend,
                       "-e", "gatewayHost", host, "-e", "gatewayPort", port, "-e", "model", model, runner]
            result = subprocess.run(adb + ["shell", shlex.join(command)], capture_output=True, text=True, timeout=90)
            if result.stdout:
                print(result.stdout.rstrip(), flush=True)
            if result.stderr:
                print(result.stderr.rstrip(), flush=True)
            if result.returncode or success not in result.stdout:
                failed.append(backend)
    finally:
        if mapping:
            subprocess.run(adb + ["reverse", "--remove", f"tcp:{mapping}"], timeout=5)
    if failed:
        raise SystemExit("Remote verification failed for " + ", ".join(failed))


if __name__ == "__main__":
    main()
