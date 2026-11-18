"""Force-stop only the isolated sample fixture, then verify durable task state."""

import argparse
from queue import Queue
import re
import subprocess
import threading
import time

PACKAGE = "dev.androidagent.sample.fixture"
RUNNER = PACKAGE + ".test/dev.androidagent.sample.SampleProbe"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("serial", help="ADB serial of the test device with the fixture APK installed")
    args = parser.parse_args()
    adb = ["adb", "-s", args.serial, "shell"]
    start = adb + ["am", "instrument", "-w", "-r", "-e", "stage", "interrupt", RUNNER]
    ready = Queue()
    with subprocess.Popen(start, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          text=True, bufsize=1) as process:
        def consume():
            for line in process.stdout:
                ready.put(line)
            ready.put(None)

        threading.Thread(target=consume, daemon=True).start()
        try:
            deadline = time.monotonic() + 25
            while True:
                line = ready.get(timeout=max(deadline - time.monotonic(), 0))
                if line is None:
                    raise RuntimeError("Fixture exited before it began the run.")
                print(line, end="")
                match = re.fullmatch(r"INSTRUMENTATION_STATUS: agent_run_ready=([0-9a-f-]{36})\s*", line)
                if match:
                    break
            subprocess.run(adb + ["am", "force-stop", PACKAGE], check=True, timeout=5)
            process.wait(timeout=5)
        finally:
            if process.poll() is None:
                process.terminate()

    recover = subprocess.run(
        adb + ["am", "instrument", "-w", "-e", "stage", "recover", "-e", "runId", match[1], RUNNER],
        check=True, capture_output=True, text=True, timeout=25,
    )
    print(recover.stdout, end="")
    if "Sample process-death probe passed" not in recover.stdout:
        raise RuntimeError("No valid recovery outcome.")


if __name__ == "__main__":
    main()
