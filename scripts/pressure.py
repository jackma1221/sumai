import concurrent.futures, urllib.request, time, sys, statistics

URL = sys.argv[1]
CONC = int(sys.argv[2])
DURATION = int(sys.argv[3])

lat = []
ok = 0
bad = 0
end = time.time() + DURATION

def one(_):
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(URL, timeout=10) as r:
            r.read()
            return (time.perf_counter() - t0) * 1000, r.status
    except urllib.error.HTTPError as e:
        e.read()
        return (time.perf_counter() - t0) * 1000, e.code
    except Exception:
        return (time.perf_counter() - t0) * 1000, -1

start = time.time()
with concurrent.futures.ThreadPoolExecutor(max_workers=CONC) as ex:
    while time.time() < end:
        futures = [ex.submit(one, i) for i in range(CONC)]
        for f in concurrent.futures.as_completed(futures):
            ms, code = f.result()
            lat.append(ms)
            if code == 200:
                ok += 1
            else:
                bad += 1
elapsed = time.time() - start
lat.sort()
def p(q):
    return round(lat[min(len(lat) - 1, int(len(lat) * q))], 2)
print(f"url={URL}")
print(f"concurrency={CONC} duration={elapsed:.1f}s total={len(lat)} success={ok} fail={bad}")
print(f"qps={len(lat)/elapsed:.1f} success_qps={ok/elapsed:.1f}")
print(f"avg={round(statistics.mean(lat),2)}ms p50={p(0.5)}ms p90={p(0.9)}ms p99={p(0.99)}ms max={round(lat[-1],2)}ms")
