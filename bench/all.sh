#!/usr/bin/env bash
# 전체 측정: 워밍업 → (서버 1대, 2대) × (1,000명, 10,000명) × 방식 7개를 3회 반복 → 표 만들기.
# 서버 1대와 2대를 번갈아 돌려서 시간에 따른 차이(캐시, 백그라운드 작업)가 한쪽에만 몰리지 않게 한다.
set -euo pipefail
cd "$(dirname "$0")/.."

rm -rf bench/results/raw
if [ "${SKIP_WARMUP:-0}" != "1" ]; then
  rm -rf bench/results/warmup
  echo "== 워밍업 (결과는 버린다)"
  OUT_DIR=bench/results/warmup bench/run.sh 2 2000 > /dev/null
  OUT_DIR=bench/results/warmup bench/run.sh 1 2000 > /dev/null
fi

for REP in 1 2 3; do
  for USERS in 1000 10000; do
    for SERVERS in 1 2; do
      echo "== 반복 ${REP}, 서버 ${SERVERS}대, ${USERS}명"
      REP=$REP bench/run.sh "$SERVERS" "$USERS"
    done
  done
done
python3 bench/report.py > bench/results/summary.md
echo "== 표: bench/results/summary.md"
