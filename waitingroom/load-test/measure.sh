#!/usr/bin/env bash
# 한 회 측정: 새 모델을 열고, 입장 속도를 정하고, k6 를 돌리고, 리더가 실제로 들인 인원 · 시간을 지표에서 뺀다.
# 사용: ./measure.sh <productId> <초당 입장 인원> <이름> [모델 상한]
set -euo pipefail
cd "$(dirname "$0")"
PRODUCT=$1
RATE=$2
NAME=$3

# 노드마다 지표를 받아 더한다. 받지 못하면 0 으로 적지 않고 멈춘다 — 0 은 "아무도 안 들였다" 와 구별되지 않는다
admitted() {
  local total=0 metrics count
  for port in 9085 9086; do
    if ! metrics=$(curl -sf "http://localhost:$port/actuator/prometheus"); then
      echo "노드 :$port 지표를 받지 못했다" >&2
      exit 1
    fi
    count=$(printf '%s\n' "$metrics" | grep -E "^waitingroom_admitted_total\{product=\"$PRODUCT\"" | awk '{s+=$2} END {print s+0}')
    total=$(awk -v a="$total" -v b="$count" 'BEGIN {print a + b}')
  done
  echo "$total"
}

./run-local.sh seed "$PRODUCT"
./run-local.sh rate "$RATE" >/dev/null
if [ -n "${4:-}" ]; then
  ./run-local.sh cap "$PRODUCT" "$4" >/dev/null
fi
sleep 2
start=$(date +%s)
# 임계값을 어기면 k6 가 실패로 끝난다. 로그와 리더 지표를 남긴 뒤 그 상태로 끝내 실패한 측정이 성공처럼 보이지 않게
k6_status=0
k6 run --quiet -e BASE_URLS=http://localhost:8085,http://localhost:8086 -e PRODUCT_ID="$PRODUCT" \
  -e TOKENS_FILE=build/tokens.csv --summary-export "build/$NAME.json" entry-spike.js > "build/$NAME.txt" 2>&1 || k6_status=$?
end=$(date +%s)
echo "$NAME: 리더가 들인 인원 $(admitted) 명 / k6 $((end - start))초 / k6 종료 코드 $k6_status" | tee -a "build/$NAME.txt"
exit "$k6_status"
