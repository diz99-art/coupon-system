#!/usr/bin/env bash
# 발급 요청 1건이 실행하는 SQL(MySQL general log)과 Redis 명령(MONITOR)을 모은다.
# 사용: bench/trace-request.sh   (서버 2대 cluster가 떠 있어야 한다. 요청은 8091 → app1)
# Kafka 컨슈머의 DB 저장(INSERT)도 함께 잡힌다. 어느 연결(서버)에서 실행됐는지 앞에 붙는다.
set -euo pipefail
cd "$(dirname "$0")/.."

# root 비밀번호는 MySQL 컨테이너의 환경변수(.env에서 온 값)를 그 안에서 읽어 쓴다.
mysql_root() {
  docker exec coupon-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot "$@"' mysql -N -B "$@"
}
MYSQL=mysql_root
COUPON_ID=$(curl -sf -X POST http://localhost:8091/coupons -H 'Content-Type: application/json' \
  -d '{"name":"요청 추적","totalQuantity":10,"issueStartAt":"2020-01-01T00:00:00","issueEndAt":"2099-01-01T00:00:00"}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
# 매분 0초의 대사 작업과 겹치지 않게 피한다.
while [ "$(date +%S)" -ge 55 ] || [ "$(date +%S)" -le 3 ]; do sleep 1; done

$MYSQL -e "SET GLOBAL log_output='TABLE'; TRUNCATE mysql.general_log; SET GLOBAL general_log='ON';" 2>/dev/null
MONITOR_OUT=$(mktemp)
docker exec coupon-redis redis-cli MONITOR > "$MONITOR_OUT" &
MONITOR_PID=$!
sleep 1

echo "── 요청: POST /coupons/$COUPON_ID/issue (userId 1) ──"
curl -s -X POST "http://localhost:8091/coupons/$COUPON_ID/issue" -H 'Content-Type: application/json' -d '{"userId":1}'
echo
sleep 2   # 컨슈머가 DB에 저장할 시간

kill "$MONITOR_PID" 2>/dev/null || true
$MYSQL -e "SET GLOBAL general_log='OFF';" 2>/dev/null
APP1_IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' coupon-app1)

echo
echo "── SQL (MySQL general log, 연결 번호 / 서버) ──"
$MYSQL -e "SELECT l.thread_id, IF(l.user_host LIKE '%$APP1_IP%', 'app1', 'app2'), REPLACE(CONVERT(l.argument USING utf8mb4), '\n', ' ')
  FROM mysql.general_log l WHERE l.command_type IN ('Query','Execute') AND l.user_host LIKE 'coupon%'
  ORDER BY l.event_time" 2>/dev/null \
  | sed -E 's/\t/ | /g; s/(.{170}).*/\1.../'
echo
echo "── Redis 명령 (MONITOR) ──"
# 연결 확인용 PING, INFO는 뺀다.
grep -v -i '"MONITOR"\|^OK\|"ping"\|"INFO"' "$MONITOR_OUT" | sed -E 's/^[0-9.]+ \[0 [0-9.:]+\] //; s/(.{170}).*/\1.../'
rm "$MONITOR_OUT"
