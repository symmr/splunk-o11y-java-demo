"""A badly behaved batch job that shares CPU cores with the shop app.

It polls the shop's chaos API for the `noisy-neighbor` scenario and, while that is on,
runs one CPU-burning worker per available core. Each worker is busy for `intensity`% of
every 100ms (capped at 90%), and the container has a much higher CPU weight than the app,
so the app is left with roughly the remaining share of the core(s).
Processes rename themselves to `noisy-batch` so they are easy to spot in the host's process list.

Safety valve: if the chaos API cannot be reached for ~30s (e.g. the app is starved so badly
it cannot answer), the workers stop so the demo can recover.
"""
import ctypes
import json
import multiprocessing as mp
import os
import time
import urllib.request

CHAOS_URL = os.environ.get("CHAOS_URL", "http://app:8080/chaos/api/scenarios/noisy-neighbor")
POLL_SECONDS = 2
MAX_FAILED_POLLS = 15
PERIOD = 0.1


def set_process_name(name):
    try:
        PR_SET_NAME = 15
        ctypes.CDLL("libc.so.6").prctl(PR_SET_NAME, name.encode()[:15], 0, 0, 0)
    except OSError:
        pass


def burn(duty):
    set_process_name("noisy-batch")
    x = 0
    while True:
        start = time.monotonic()
        busy_until = start + PERIOD * duty.value
        while time.monotonic() < busy_until:
            x = (x * 31 + 7) % 1_000_003
        rest = start + PERIOD - time.monotonic()
        if rest > 0:
            time.sleep(rest)


def fetch_setting():
    """Returns the duty cycle (0..0.9), or None if the chaos API is unreachable."""
    try:
        with urllib.request.urlopen(CHAOS_URL, timeout=2) as res:
            data = json.load(res)
            if not data.get("enabled"):
                return 0.0
            return min(0.9, int(data.get("intensity", 70)) / 100)
    except Exception:
        return None


def main():
    set_process_name("noisy-batch")
    cores = len(os.sched_getaffinity(0))
    duty = mp.Value("d", 0.0, lock=False)
    workers = []
    failed = 0
    while True:
        setting = fetch_setting()
        if setting is None:
            failed += 1
            if failed >= MAX_FAILED_POLLS:
                setting = 0.0
        else:
            failed = 0
        if setting is not None:
            duty.value = setting
            if setting > 0 and not workers:
                workers = [mp.Process(target=burn, args=(duty,), daemon=True) for _ in range(cores)]
                for w in workers:
                    w.start()
            elif setting == 0 and workers:
                for w in workers:
                    w.terminate()
                workers = []
        time.sleep(POLL_SECONDS)


if __name__ == "__main__":
    main()
