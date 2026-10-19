#!/usr/bin/env bash
# 7단계: Redis+Kafka 방식에 10,000명 시나리오를 여러 번 연달아 보내고, 그 구간의 Grafana 대시보드를 캡처한다.
# 사용: bench/monitor.sh <반복 횟수> [쿠폰 수량, 기본 100] [캡처 파일]
#   예) bench/monitor.sh 5               (100장에 10,000명 × 5번)
#       bench/monitor.sh 3 10000 docs/images/grafana-10000.png   (전원 발급: Kafka·DB 저장까지 부하)
# 한 번(10,000명)은 몇 초 만에 끝나 그래프에 점 몇 개뿐이라, 여러 번 이어 보내 지표 변화를 본다.
set -euo pipefail
cd "$(dirname "$0")/.."

ROUNDS=${1:-5}
export QUANTITY=${2:-100}
CAPTURE=${3:-docs/images/grafana-q$QUANTITY.png}
export OUT_DIR=${OUT_DIR:-bench/results/monitoring}
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"

START=$(date +%s)
for ROUND in $(seq 1 "$ROUNDS"); do
  echo "[${ROUND}/${ROUNDS}] 10,000명 → 쿠폰 ${QUANTITY}장 (REDIS_KAFKA, 서버 2대)"
  REP="q$QUANTITY-r$ROUND" bench/run.sh 2 10000 REDIS_KAFKA
done
# Prometheus가 마지막 지표를 가져가고 rate() 구간이 지나가도록 조금 기다린다.
sleep 15
END=$(date +%s)

URL="http://localhost:3000/d/coupon/?orgId=1&from=$(( (START - 20) * 1000 ))&to=$(( END * 1000 ))&kiosk"
echo "Grafana: ${URL%&kiosk}"
if [ -x "$CHROME" ]; then
  mkdir -p "$(dirname "$CAPTURE")"
  PROFILE=$(mktemp -d)
  "$CHROME" --headless=new --disable-gpu --hide-scrollbars --user-data-dir="$PROFILE" \
    --window-size=1600,1640 --virtual-time-budget=20000 --screenshot="$PWD/$CAPTURE" "$URL" 2>/dev/null
  rm -rf "$PROFILE"
  echo "캡처: $CAPTURE"
fi
