#!/usr/bin/env bash
# 8단계: 캐시 적용 전후 비교. Redis+Kafka 방식, 서버 2대, 10,000명 → 쿠폰 100장을 ROUNDS번 잰다.
# 사용: bench/cache-bench.sh <이름(before|after)> [ROUNDS=3] [WARMUP=9]
# 회차마다 k6 결과와 함께 MySQL 실행 문장 수(SHOW GLOBAL STATUS), Redis 명령 수(INFO commandstats)의 증가량을 저장하고,
# 마지막에 측정 구간의 Grafana 대시보드를 캡처한다 (docs/images/grafana-cache-<이름>.png).
set -euo pipefail
cd "$(dirname "$0")/.."

LABEL=$1
ROUNDS=${2:-3}
WARMUP=${3:-9}
OUT=bench/results/cache
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
mkdir -p "$OUT"

mysql_status() {
  docker exec coupon-mysql mysql -uroot -proot -N -B -e \
    "SHOW GLOBAL STATUS WHERE Variable_name IN ('Questions','Com_select','Com_insert','Com_commit','Com_set_option')" 2>/dev/null \
    | awk '{printf "\"%s\":%s,", $1, $2}' | sed 's/,$//; s/^/{/; s/$/}/'
}
redis_status() {
  docker exec coupon-redis redis-cli INFO commandstats \
    | python3 -c 'import sys,re,json; print(json.dumps({m[1]: int(m[2]) for m in re.finditer(r"cmdstat_(\w+):calls=(\d+)", sys.stdin.read())}))'
}

echo "워밍업 ${WARMUP}회"
for I in $(seq 1 "$WARMUP"); do
  OUT_DIR=bench/results/warmup REP="$LABEL-w$I" bench/run.sh 2 10000 REDIS_KAFKA | grep -o 'tps=[0-9]*' || true
done

START=$(date +%s)
for ROUND in $(seq 1 "$ROUNDS"); do
  BEFORE_SQL=$(mysql_status); BEFORE_REDIS=$(redis_status)
  OUT_DIR=$OUT REP="$LABEL-r$ROUND" bench/run.sh 2 10000 REDIS_KAFKA
  AFTER_SQL=$(mysql_status); AFTER_REDIS=$(redis_status)
  FILE="$OUT/servers2-10000-REDIS_KAFKA-r$LABEL-r$ROUND.json"
  python3 - "$FILE" "$BEFORE_SQL" "$AFTER_SQL" "$BEFORE_REDIS" "$AFTER_REDIS" <<'PY'
import json, sys
path, b_sql, a_sql, b_redis, a_redis = sys.argv[1], *map(json.loads, sys.argv[2:])
result = json.load(open(path))
n = result["requests"]
result["mysql"] = {k: a_sql[k] - b_sql[k] for k in a_sql}
result["redis"] = {k: a_redis[k] - b_redis.get(k, 0) for k in a_redis if a_redis[k] != b_redis.get(k, 0)}
result["perRequest"] = {"mysqlStatements": result["mysql"]["Questions"] / n,
                        "mysqlSelects": result["mysql"]["Com_select"] / n,
                        "redisCommands": sum(result["redis"].values()) / n}
json.dump(result, open(path, "w"), ensure_ascii=False, indent=2)
print(f"  요청 1건당 MySQL 문장 {result['perRequest']['mysqlStatements']:.2f}개 (SELECT {result['perRequest']['mysqlSelects']:.2f}), "
      f"Redis 명령 {result['perRequest']['redisCommands']:.2f}개 {result['redis']}")
PY
done
sleep 15
END=$(date +%s)
echo "측정 구간: $START ~ $END"
echo "$START $END" > "$OUT/$LABEL-window.txt"

URL="http://localhost:3000/d/coupon/?orgId=1&from=$(( (START - 6) * 1000 ))&to=$(( END * 1000 ))&kiosk"
if [ -x "$CHROME" ]; then
  PROFILE=$(mktemp -d)
  "$CHROME" --headless=new --disable-gpu --hide-scrollbars --user-data-dir="$PROFILE" \
    --window-size=1600,1960 --virtual-time-budget=20000 --screenshot="$PWD/docs/images/grafana-cache-$LABEL.png" "$URL" 2>/dev/null
  rm -rf "$PROFILE"
  echo "캡처: docs/images/grafana-cache-$LABEL.png"
fi
