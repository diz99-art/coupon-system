"""bench/results/raw의 측정 결과를 방식·서버 수·사용자 수별 중앙값 표로 만든다."""
import glob
import json
import statistics
from collections import defaultdict

ORDER = ["NO_LOCK", "SYNCHRONIZED", "PESSIMISTIC_LOCK", "OPTIMISTIC_LOCK", "REDISSON_LOCK", "REDIS_LUA", "REDIS_KAFKA"]
NAMES = {
    "NO_LOCK": "락 없음",
    "SYNCHRONIZED": "synchronized (트랜잭션 바깥)",
    "PESSIMISTIC_LOCK": "비관적 락",
    "OPTIMISTIC_LOCK": "낙관적 락",
    "REDISSON_LOCK": "Redisson 분산 락",
    "REDIS_LUA": "Redis Lua (동기 저장)",
    "REDIS_KAFKA": "Redis Lua + Kafka",
}

runs = defaultdict(list)
for path in glob.glob("bench/results/raw/*.json"):
    r = json.load(open(path))
    servers = 1 if r["label"] == "servers1" else 2
    runs[(r["users"], r["method"], servers)].append(r)


def med(values):
    return statistics.median(values)


def cell(rs):
    rows = sorted(r["server"]["issueRows"] for r in rs)
    failed = sum(r["failed"] for r in rs)
    issued = "/".join(str(x) for x in rows)
    return (f"{med([r['tps'] for r in rs]):.0f}", f"{med([r['avgMs'] for r in rs]):.0f}",
            f"{med([r['p95Ms'] for r in rs]):.0f}", issued, str(failed))


for users in sorted({k[0] for k in runs}):
    print(f"\n### 동시 사용자 {users:,}명 (쿠폰 100장, 3회 중앙값)\n")
    print("| 방식 | 서버 | TPS | 평균 응답(ms) | p95(ms) | 실제 발급 (3회) | 오류 응답 |")
    print("|---|---|---|---|---|---|---|")
    for method in ORDER:
        for servers in (1, 2):
            rs = runs.get((users, method, servers))
            if not rs:
                continue
            tps, avg, p95, issued, failed = cell(rs)
            print(f"| {NAMES[method]} | {servers}대 | {tps} | {avg} | {p95} | {issued} | {failed} |")
