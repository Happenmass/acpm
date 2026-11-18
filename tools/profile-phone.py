"""Measure sample startup to the first model request and sampled process-tree RSS."""

import argparse
import re
import subprocess
import time

PACKAGE = "dev.androidagent.sample.fixture"
RUNNER = PACKAGE + ".test/dev.androidagent.sample.SampleProbe"


def rss_snapshot(adb):
    output = subprocess.check_output(adb + ["ps", "-A", "-o", "PID,PPID,RSS,NAME"],
                                     text=True, timeout=4)
    rows = {}
    roots = []
    for row in output.splitlines()[1:]:
        parts = row.strip().split(None, 3)
        if len(parts) != 4 or not all(field.isdecimal() for field in parts[:3]):
            continue
        pid, parent, rss = map(int, parts[:3])
        rows[pid] = (parent, rss, parts[3])
        if parts[3] == PACKAGE:
            roots.append(pid)
    family = set(roots)
    while True:
        children = {pid for pid, (parent, _, _) in rows.items() if parent in family} - family
        if not children:
            break
        family.update(children)
    return sum(rows[pid][1] for pid in family), len(family)


def run(adb, backend, cache):
    command = adb + ["am", "instrument", "-w", "-e", "stage", "profile", "-e", "backend", backend, RUNNER]
    with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True) as probe:
        max_rss = 0
        max_count = 0
        samples = 0
        try:
            deadline = time.monotonic() + 40
            while probe.poll() is None:
                if time.monotonic() > deadline:
                    raise RuntimeError("Profile probe timed out")
                rss, count = rss_snapshot(adb)
                max_rss = max(max_rss, rss)
                max_count = max(max_count, count)
                samples += 1
                time.sleep(0.075)
            output = probe.stdout.read()
        finally:
            if probe.poll() is None:
                probe.terminate()
        latency = re.search(r"firstRequestMs=(\d+)", output)
        if probe.returncode or "Sample UI probe passed" not in output or latency is None or max_count <= 1:
            raise RuntimeError(f"Profile evidence incomplete for {backend}: {output}")
        print(f"{backend} {cache}: firstRequestMs={latency[1]}, peakTreeRssKiB={max_rss}, processCount={max_count}, samples={samples}",
              flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("serial")
    args = parser.parse_args()
    adb = ["adb", "-s", args.serial, "shell"]
    for backend in ("pi", "codex"):
        # This package contains only synthetic runs. Clear it before each adapter's cold trial.
        cleared = subprocess.run(adb + ["pm", "clear", PACKAGE], check=True, capture_output=True, text=True, timeout=15)
        if cleared.stdout.strip() != "Success":
            raise RuntimeError(f"Could not clear the isolated fixture: {cleared.stdout}")
        run(adb, backend, "cold-cache")
        run(adb, backend, "warm-cache")


if __name__ == "__main__":
    main()
