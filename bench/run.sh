#!/usr/bin/env bash
# 방식마다 새 쿠폰(100장)을 만들고 k6로 동시 요청을 보낸 뒤, 서버에서 실제 발급 결과를 받아 저장한다.
# 사용: bench/run.sh <서버 수 1|2> <USERS> [방식...]
#   예) bench/run.sh 1 1000      (nginx → 서버 1대)
#       bench/run.sh 2 10000     (nginx → 서버 2대)
# k6는 compose 네트워크 안의 컨테이너로 실행해서 nginx에 바로 요청한다.
# 내 컴퓨터에서 실행하면 Docker Desktop의 포트 전달 구간이 먼저 병목이 되어 서버 차이가 가려진다.
set -euo pipefail
cd "$(dirname "$0")/.."

SERVERS=$1
USERS=$2
shift 2
case $SERVERS in
  1) BASE_URL=http://localhost:8091; K6_TARGET=http://nginx:81 ;;
  2) BASE_URL=http://localhost:8090; K6_TARGET=http://nginx:80 ;;
  *) echo "서버 수는 1 또는 2"; exit 1 ;;
esac
LABEL="servers$SERVERS"
K6_IMAGE=grafana/k6:2.3.0
NETWORK=coupon-system_default
METHODS=${*:-NO_LOCK SYNCHRONIZED PESSIMISTIC_LOCK OPTIMISTIC_LOCK REDISSON_LOCK REDIS_LUA REDIS_KAFKA}
QUANTITY=100
OUT_DIR=${OUT_DIR:-bench/results/raw}
REP=${REP:-1}
mkdir -p "$OUT_DIR"

for METHOD in $METHODS; do
  COUPON_ID=$(curl -sf -X POST "$BASE_URL/coupons" -H 'Content-Type: application/json' \
    -d "{\"name\":\"bench $METHOD\",\"totalQuantity\":$QUANTITY,\"issueStartAt\":\"2020-01-01T00:00:00\",\"issueEndAt\":\"2099-01-01T00:00:00\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
  FILE="$OUT_DIR/$LABEL-$USERS-$METHOD-r$REP.json"

  docker run --rm --network "$NETWORK" --ulimit nofile=65535:65535 \
    --sysctl net.ipv4.ip_local_port_range="1024 65535" --sysctl net.ipv4.tcp_tw_reuse=1 \
    -v "$PWD/bench:/bench" "$K6_IMAGE" \
    run --quiet -e BASE_URL="$K6_TARGET" -e COUPON_ID="$COUPON_ID" -e METHOD="$METHOD" -e USERS="$USERS" \
    -e OUTPUT="/$FILE.k6" /bench/issue.js

  # Kafka 방식은 응답 뒤에 DB 저장이 이어지므로, DB 발급 건수가 더 바뀌지 않을 때까지 기다린다.
  PREV=-1
  for _ in $(seq 1 60); do
    RESULT=$(curl -sf "$BASE_URL/bench/coupons/$COUPON_ID/result")
    ROWS=$(echo "$RESULT" | python3 -c 'import json,sys; print(json.load(sys.stdin)["issueRows"])')
    [ "$ROWS" = "$PREV" ] && break
    PREV=$ROWS
    sleep 1
  done
  python3 - "$FILE.k6" "$FILE" "$LABEL" "$RESULT" <<'PY'
import json, sys
k6 = json.load(open(sys.argv[1]))
k6["label"] = sys.argv[3]
k6["server"] = json.loads(sys.argv[4])
json.dump(k6, open(sys.argv[2], "w"), ensure_ascii=False, indent=2)
PY
  rm "$FILE.k6"
  echo "  → 실제 발급(DB 기록) $(echo "$RESULT" | python3 -c 'import json,sys; r=json.load(sys.stdin); print(r["issueRows"], "/ Redis", r["redisCount"])')"
  sleep 2
done
