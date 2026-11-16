"""Throughput, startup and RSS of a petstore executable.

    python bench.py <exe> <workdir> [requests per endpoint] [extra args for the exe...]

One keep-alive connection, sequential; the same measurement as in the Windows sessions, so numbers compare. Works on
Windows and Linux. Start-up is the time until /pet/2 answers 200. The server listens on 127.0.0.1:8080.
"""
import http.client
import os
import subprocess
import sys
import time

exe, cwd = sys.argv[1], sys.argv[2]
N = int(sys.argv[3]) if len(sys.argv) > 3 else 5000
extra = sys.argv[4:]
t0 = time.time()
p = subprocess.Popen([exe] + extra, cwd=cwd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
c = None
while time.time() - t0 < 120:
    try:
        c = http.client.HTTPConnection('127.0.0.1', 8080, timeout=5)
        c.request('GET', '/pet/2')
        r = c.getresponse()
        r.read()
        if r.status == 200:
            break
    except Exception:
        time.sleep(0.1)
start = time.time() - t0


def run(path, n):
    lat = []
    for _ in range(n):
        s = time.perf_counter()
        c.request('GET', path)
        r = c.getresponse()
        r.read()
        lat.append(time.perf_counter() - s)
        assert r.status == 200, (path, r.status)
    return lat


run('/pet/2', 500)  # warm-up
out = {}
for path in ('/pet/2', '/pet/findByStatus?status=available', '/store/inventory'):
    s = time.perf_counter()
    lat = run(path, N)
    el = time.perf_counter() - s
    lat.sort()
    out[path] = (N / el, lat[len(lat) // 2] * 1000, lat[int(len(lat) * 0.99)] * 1000)


def rss_mb(pid):
    if sys.platform.startswith('linux'):
        with open(f'/proc/{pid}/status') as f:
            for line in f:
                if line.startswith('VmRSS:'):
                    return int(line.split()[1]) / 1024
        return float('nan')
    text = subprocess.run(['powershell', '-NoProfile', '-Command',
                           f'(Get-Process -Id {pid}).WorkingSet64/1MB'], capture_output=True, text=True).stdout.strip()
    return float(text.replace(',', '.'))


print(f'{os.path.basename(exe)}: start {start:.1f}s, rss {rss_mb(p.pid):.0f} MB')
for k, (rps, p50, p99) in out.items():
    print(f'  {k}: {rps:.0f} req/s, p50 {p50:.2f} ms, p99 {p99:.2f} ms')
if sys.platform.startswith('linux'):
    p.kill()
else:
    subprocess.run(['taskkill', '/F', '/PID', str(p.pid)], capture_output=True)
