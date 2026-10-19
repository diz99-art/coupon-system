#!/usr/bin/env bash
# 서버 2대에서 대사 작업이 매분 몇 대에서 실행되는지 로그로 확인한다.
# 사용: bench/reconcile-check.sh [지켜볼 분 수, 기본 3]
# 1) 쿠폰을 만들고, Redis에만 있는 발급 5건(Kafka 메시지가 유실됐다고 가정)을 넣는다.
# 2) 정해진 분 동안 app1, app2 로그에서 "대사 작업 시작"을 분 단위로 센다.
# 3) 매분 정확히 한 서버만 실행했으면 통과(종료 코드 0), 아니면 실패(1).
set -euo pipefail
cd "$(dirname "$0")/.."

MINUTES=${1:-3}
SINCE=$(date -u +%Y-%m-%dT%H:%M:%SZ)

COUPON_ID=$(curl -sf -X POST http://localhost:8090/coupons -H 'Content-Type: application/json' \
  -d '{"name":"대사 확인","totalQuantity":100,"issueStartAt":"2020-01-01T00:00:00","issueEndAt":"2099-01-01T00:00:00"}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
# 1시간 전에 발급된 것으로 넣는다 (대사 유예 시간 30초보다 오래됨).
ISSUED_AT=$(( ($(date +%s) - 3600) * 1000 ))
for USER_ID in 1 2 3 4 5; do
  docker exec coupon-redis redis-cli ZADD "coupon:$COUPON_ID:users" "$ISSUED_AT" "$USER_ID" > /dev/null
done
docker exec coupon-redis redis-cli SET "coupon:$COUPON_ID:count" 5 > /dev/null
echo "쿠폰 $COUPON_ID: Redis에만 있는 발급 5건을 넣었다. ${MINUTES}분 동안 지켜본다."

# 지금 분이 끝나고 MINUTES번의 정각(0초)이 지나갈 때까지 기다린다.
sleep $(( 60 - $(date +%S | sed 's/^0//') + (MINUTES - 1) * 60 + 5 ))

LOGS=$(mktemp)
for APP in app1 app2; do
  docker logs --since "$SINCE" "coupon-$APP" 2>&1 | grep -E "대사 작업 시작|대사 작업 끝|대사 보정|이미 저장된 발급|It.s locked|Locked .couponReconciliation" \
    | sed "s/^/$APP /" >> "$LOGS" || true
done

echo
echo "── 대사 작업 로그 (시각순) ──"
# 로그 줄: <서버> <날짜>T<시각> ... → 시각 기준으로 정렬
sort -k2 "$LOGS" | sed -E 's/^(app[12]) ([0-9-]+T[0-9:.]+)[^ ]* +[A-Z]+ +[0-9]+ --- \[[^]]*\] \[[^]]*\] [^ ]+ +: /\1 \2  /'

echo
echo "── 분마다 실행한 서버 ──"
FAIL=0
while read -r MINUTE; do
  SERVERS=$(grep "대사 작업 시작" "$LOGS" | grep -F "T$MINUTE" | awk '{print $1}' | sort | tr '\n' ' ')
  COUNT=$(echo "$SERVERS" | wc -w | tr -d ' ')
  echo "$MINUTE  실행 ${COUNT}대: $SERVERS"
  [ "$COUNT" = "1" ] || FAIL=1
done < <(grep "대사 작업 시작" "$LOGS" | awk '{print $2}' | sed -E 's/.*T([0-9]{2}:[0-9]{2}).*/\1/' | sort -u)
rm "$LOGS"

echo
echo "DB 발급 기록: $(curl -sf "http://localhost:8090/bench/coupons/$COUPON_ID/result" | python3 -c 'import json,sys; print(json.load(sys.stdin)["issueRows"])')건 (기대 5건)"
if [ "$FAIL" = 0 ]; then echo "통과: 매분 한 서버만 대사 작업을 실행했다."; else echo "실패: 같은 분에 두 서버가 함께 실행했다."; exit 1; fi
