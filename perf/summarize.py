"""Prints the key numbers from a k6 --summary-export file:  python3 perf/summarize.py perf/results/<file>.json"""
import json, sys
m = json.load(open(sys.argv[1]))["metrics"]
for name, v in sorted(m.items()):
    if name.startswith("http_req_duration"):
        print(f"{name:42} avg {v['avg']:7.1f}  med {v['med']:7.1f}  p90 {v['p(90)']:7.1f}  p95 {v['p(95)']:7.1f}  max {v['max']:8.1f} ms")
r = m.get("http_reqs"); f = m.get("http_req_failed")
print(f"requests {r['count']}  ({r['rate']:.1f}/s)   failed {100*f['value']:.2f}%")
