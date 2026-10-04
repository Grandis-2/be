#!/usr/bin/env bash
# 부하 시험용 로컬 환경 — Redis 8 하나와 대기열 노드 N 대. SQS 는 쓰지 않고 회차 일정은 Redis 에 직접 넣는다.
# 사용: ./run-local.sh start [노드 수] | seed <productId> | rate <초당 인원> | cap <productId> <초당 인원> | stop
# 먼저: ./gradlew :waitingroom:bootJar && python3 make-tokens.py --count 5000 --out build
set -euo pipefail
cd "$(dirname "$0")"

REDIS_NAME=wr-load-redis
REDIS_PORT=${REDIS_PORT:-16379}
JAR=../build/libs/waitingroom-0.0.1-SNAPSHOT.jar
# 모듈이 Java 25 로 빌드된다. 셸의 JAVA_HOME 이 다른 버전이어도 25 를 쓴다
JAVA=${JAVA25:-$HOME/.sdkman/candidates/java/25-amzn/bin/java}
SECRET=${TOKEN_SECRET:-load-test-token-secret-0123456789}

start() {
  local nodes=${1:-2}
  docker run -d --rm --name "$REDIS_NAME" -p "$REDIS_PORT:6379" redis:8 --maxmemory-policy noeviction >/dev/null
  local pem
  pem=$(cat build/public-key.pem)
  local config
  config=$(jq -n --arg pem "$pem" --arg secret "$SECRET" --argjson port "$REDIS_PORT" \
    '{jwt: {issuer: "nova", audience: "nova-api", "jwk-set-uri": "", "public-keys": {"load-test": $pem}},
      waitingroom: {token: {secret: $secret}}, spring: {data: {redis: {host: "localhost", port: $port}}}}')
  for i in $(seq 0 $((nodes - 1))); do
    SPRING_APPLICATION_JSON="$config" nohup "$JAVA" -Xmx1g -jar "$JAR" \
      --server.port=$((8085 + i)) --management.server.port=$((9085 + i)) \
      --logging.level.root=WARN > "build/node-$i.log" 2>&1 &
    echo $! > "build/node-$i.pid"
  done
  for i in $(seq 0 $((nodes - 1))); do
    until curl -sf "http://localhost:$((9085 + i))/actuator/health/liveness" >/dev/null; do sleep 1; done
  done
  echo "Redis :$REDIS_PORT, 노드 $nodes 대(:8085~, 관리 :9085~)"
}

# 오픈 1분 전부터 2시간 접수. 일정 번호는 지금 시각이라 다시 넣으면 더 큰 번호다
seed() {
  local now
  now=$(date +%s%3N)
  docker exec "$REDIS_NAME" redis-cli HSET wr:products "$1" "$((now - 60000))|$((now + 7200000))|$now" >/dev/null
  echo "모델 $1 접수 중(2시간)"
}

rate() {
  curl -sf -X PUT "http://localhost:8085/api/v1/admin/waitingroom/admission-rate" \
    -H "Authorization: Bearer $(cat build/admin-token.txt)" -H 'Content-Type: application/json' \
    -d "{\"globalCredit\": $1}" | jq -c '.data.globalCredit'
}

# 모델별 상한 운영값. 기본값(150)보다 높은 속도를 잴 때 쓴다
cap() {
  curl -sf -X PUT "http://localhost:8085/api/v1/admin/waitingroom/products/$1/admission-rate" \
    -H "Authorization: Bearer $(cat build/admin-token.txt)" -H 'Content-Type: application/json' \
    -d "{\"cap\": $2}" >/dev/null
  echo "모델 $1 상한 $2"
}

stop() {
  for pid in build/node-*.pid; do
    [ -f "$pid" ] && kill "$(cat "$pid")" 2>/dev/null || true
    rm -f "$pid"
  done
  docker rm -f "$REDIS_NAME" >/dev/null 2>&1 || true
}

"$@"
