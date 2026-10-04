#!/usr/bin/env bash
# 한 회 측정: 새 모델을 열고, 입장 속도를 정하고, k6 를 돌리고, 리더가 실제로 들인 인원 · 시간을 지표에서 뺀다.
# 사용: ./measure.sh <productId> <초당 입장 인원> <이름> [모델 상한]
set -euo pipefail
cd "$(dirname "$0")"
PRODUCT=$1
RATE=$2
NAME=$3

admitted() {
  for port in 9085 9086; do
    curl -s "http://localhost:$port/actuator/prometheus" | rg "^waitingroom_admitted_total\{product=\"$PRODUCT\"" | awk '{print $2}'
  done | awk '{s+=$1} END {print s+0}'
}

./run-local.sh seed "$PRODUCT"
./run-local.sh rate "$RATE" >/dev/null
if [ -n "${4:-}" ]; then
  ./run-local.sh cap "$PRODUCT" "$4" >/dev/null
fi
sleep 2
start=$(date +%s)
k6 run --quiet -e BASE_URLS=http://localhost:8085,http://localhost:8086 -e PRODUCT_ID="$PRODUCT" \
  -e TOKENS_FILE=build/tokens.csv --summary-export "build/$NAME.json" entry-spike.js > "build/$NAME.txt" 2>&1 || true
end=$(date +%s)
echo "$NAME: 리더가 들인 인원 $(admitted) 명 / k6 $((end - start))초" | tee -a "build/$NAME.txt"
