#!/usr/bin/env bash
# JVM AOT 캐시(JEP 483 · 514 · 515)를 만들어 서비스 이미지에 얹는다. CI(release · build)와 로컬이 같은 명령을 쓴다.
#
#   ./gradlew :<서비스>:bootJar … 후 dist/<서비스>/app.jar 를 놓고   docker/aot-training/train.sh
#
# 1) 기반 이미지(docker/Dockerfile, 캐시 없음)를 서비스마다 만든다. 베이스는 다이제스트로 고정한다.
# 2) MySQL(스키마 · 시드) · Redis · Floci(큐)를 띄운다(compose.yaml · seed.sql).
# 3) 기반 이미지를 그대로 띄워 학습한다. 캐시는 -XX:AOTCacheOutput 으로 JVM 이 끝날 때 쓰인다.
#      workload — 끝까지 띄워 대표 요청을 넣고(workload/*.js) 정상 종료한다. 기동 + 요청 경로의 클래스와 메서드 프로파일이 담긴다
#      startup  — 빈을 모두 만든 뒤 lifecycle 시작 전에 끝낸다(-Dspring.context.exit=onRefresh). 요청이 없는 서비스(worker · batch)
# 4) 학습한 그 이미지에 캐시를 얹는다(docker/Dockerfile.aot → <FINAL_REPO>:<서비스>). 다시 빌드한 이미지에 얹으면 안 된다 —
#    JVM 은 jar 가 학습 때와 같은지 보지 않아, 어긋나면 거절 대신 캐시의 옛 클래스를 실행한다(JDK 25 실측).
# 5) 검증: 최종 이미지를 그 ENTRYPOINT 에 -XX:AOTMode=on 을 더해 띄운다 — JDK · 캐시 경로가 어긋나 캐시를 받지 못하면 JVM 이 실패한다
#    (운영의 기본 auto 는 조용히 캐시 없이 뜬다). 기반 이미지(지금의 운영과 같은 기본 CDS)를 띄운 시간과 함께 표로 남긴다(SUMMARY, 없으면 표준 출력).
#
# 학습 환경은 이번 실행에서만 쓰고 버린다. 비밀값(DB · 관리자 비밀번호 · JWT 키 · 입장권 · Toss 키)은 모두 매번 새로 만든다.
#
# 환경 변수
#   BASE_IMAGE      베이스 이미지. 비우면 eclipse-temurin:25-jre 를 받아 다이제스트로 고정한다
#   FINAL_REPO      최종 이미지 저장소 이름(기본 release) — 태그는 서비스 이름
#   TRAIN_CPUS · TRAIN_MEMORY   학습 · 측정 컨테이너 자원(기본 1 · 2g). 캐시는 GC · 힙이 달라도 받아지므로(JDK 25 실측) 정확성 조건이 아니라 측정 조건이다
#   STEADY_SECONDS  요청 학습 2부의 길이(기본 90)
#   SUMMARY         검증 표를 덧붙일 파일(CI 는 $GITHUB_STEP_SUMMARY)
#   KEEP=1          끝나도 컨테이너 · 기반 시설을 지우지 않는다(디버깅)
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
HERE="$ROOT/docker/aot-training"
DIST="$ROOT/dist"
PROJECT=nova-aot
NETWORK=${PROJECT}_default
FINAL_REPO=${FINAL_REPO:-release}
TRAIN_CPUS=${TRAIN_CPUS:-1}
TRAIN_MEMORY=${TRAIN_MEMORY:-2g}
STEADY_SECONDS=${STEADY_SECONDS:-90}
# 학습 컨테이너는 각자 네트워크 네임스페이스라 서비스마다 같은 포트를 써도 된다 — 서비스별 포트 표를 두지 않는다
SERVER_PORT=8080
MANAGEMENT_PORT=8081
TOSS_PORT=18089
# 회차는 등록 뒤 MIN 이 지나야 열 수 있고(catalog 검증), 학습은 OPEN 뒤에 연다
MIN_OPEN_LEAD_SECONDS=20
OPEN_LEAD_SECONDS=40
SUMMARY=${SUMMARY:-/dev/stdout}
K6_IMAGE=grafana/k6:1.7.1
TOSS_IMAGE=wiremock/wiremock:3.13.2
CURL_IMAGE=curlimages/curl:8.16.0
WORK=$(mktemp -d)
chmod 755 "$WORK"

# DB 비밀번호는 매번 새로 만든다. compose.yaml 이 MYSQL_ROOT_PASSWORD 를 읽으므로 정리(compose down)보다 먼저 정한다
export MYSQL_ROOT_PASSWORD
MYSQL_ROOT_PASSWORD=$(openssl rand -hex 16)
DB_USER=nova
DB_PASSWORD=$(openssl rand -hex 16)

compose() { docker compose -p "$PROJECT" -f "$HERE/compose.yaml" "$@"; }
now() { python3 -c 'import time; print(time.time())'; }
log() { echo "[aot] $*" >&2; }

cleanup() {
  local rc=$?
  if [ "${KEEP:-}" != 1 ]; then
    for c in $(docker ps -aq --filter "label=$PROJECT"); do docker rm -f "$c" > /dev/null 2>&1 || true; done
    compose down -v > /dev/null 2>&1 || true
    rm -rf "$WORK"
  else
    log "남겨 둠: 컨테이너(label=$PROJECT) · 작업 디렉터리 $WORK"
  fi
  exit $rc
}
trap cleanup EXIT

# 학습 방식별 서비스. 새 서비스는 둘 중 하나에 넣어야 학습이 돈다 — 빠지면 실패한다(캐시 없는 이미지가 조용히 나가지 않게).
# 요청 학습은 서비스 사이를 오가므로 이 순서로 띄우고, 모두 있어야 한다.
WORKLOAD_SERVICES="member catalog preorder order payment waitingroom"
STARTUP_SERVICES="worker batch"
mode_of() {
  case " $WORKLOAD_SERVICES " in *" $1 "*) echo workload; return ;; esac
  case " $STARTUP_SERVICES " in *" $1 "*) echo startup; return ;; esac
  return 1
}

services=()
for jar in "$DIST"/*/app.jar; do
  [ -e "$jar" ] || { echo "dist/<서비스>/app.jar 가 없다" >&2; exit 1; }
  s=$(basename "$(dirname "$jar")")
  mode_of "$s" > /dev/null || { echo "학습 방식이 정해지지 않은 서비스: $s (train.sh 의 mode_of)" >&2; exit 1; }
  services+=("$s")
done
for s in $WORKLOAD_SERVICES; do
  [ -e "$DIST/$s/app.jar" ] || { echo "요청 학습에는 $s 도 있어야 한다(dist/$s/app.jar)" >&2; exit 1; }
done

# ── 1) 기반 이미지 ───────────────────────────────────────────────
if [ -z "${BASE_IMAGE:-}" ]; then
  docker pull -q eclipse-temurin:25-jre > /dev/null
  BASE_IMAGE=$(docker image inspect --format '{{index .RepoDigests 0}}' eclipse-temurin:25-jre)
fi
log "베이스 $BASE_IMAGE"
for s in "${services[@]}"; do
  docker build -q -f "$ROOT/docker/Dockerfile" --build-arg BASE_IMAGE="$BASE_IMAGE" \
    --build-arg JAR_FILE="dist/$s/app.jar" -t "$PROJECT/$s:base" "$ROOT" > /dev/null
done

# ── 2) 기반 시설 ─────────────────────────────────────────────────
compose up -d --wait mysql redis floci > /dev/null
compose run --rm floci-init > /dev/null
# build.yml 의 apply schema 와 같다: 정본 마이그레이션을 버전 순으로, 연결 문자셋 utf8mb4 로
# 비밀번호는 MYSQL_PWD 로 준다 — -p 경고를 숨기려고 stderr 를 버리면 마이그레이션 · 시드 실패의 원인도 사라진다
mysql_root() { compose exec -T -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql mysql -uroot --default-character-set=utf8mb4 "$@"; }
mysql_root -e "CREATE DATABASE shop DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
for migration in $(ls "$ROOT"/flyway-project/migrations/V*.sql | sort); do
  mysql_root shop < "$migration"
done
mysql_root -e "CREATE USER '$DB_USER'@'%' IDENTIFIED BY '$DB_PASSWORD'; GRANT ALL ON shop.* TO '$DB_USER'@'%'; GRANT ALL ON external_mock.* TO '$DB_USER'@'%';"
mysql_root shop < "$HERE/seed.sql"
# 시드가 정한 카테고리 · 회원을 그대로 읽어 쓴다 — id 규칙을 여기서 다시 적지 않는다
CATEGORY_ID=$(mysql_root shop -N -e "SELECT BIN_TO_UUID(id) FROM categories ORDER BY sort_order LIMIT 1")
CUSTOMERS=$(mysql_root shop -N -e "SELECT BIN_TO_UUID(id) FROM customers ORDER BY id")

# ── 학습 전용 비밀값 · 토큰 ───────────────────────────────────────
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$WORK/jwt.pem" 2> /dev/null
KEY_ID=aottraining
# 발급자 · 대상은 학습 전용 값으로 모든 서비스에 같이 준다(app_json) — 토큰과 검증 설정이 한 곳에서 나온다
JWT_ISSUER=aot-training
JWT_AUDIENCE=aot-training-api
ADMIN_PASSWORD=$(openssl rand -hex 16)
if command -v htpasswd > /dev/null; then
  ADMIN_HASH=$(htpasswd -nbBC 12 admin "$ADMIN_PASSWORD" | cut -d: -f2 | tr -d '\n')
else
  ADMIN_HASH=$(docker run --rm httpd:2.4-alpine htpasswd -nbBC 12 admin "$ADMIN_PASSWORD" | cut -d: -f2 | tr -d '\r\n')
fi
WAITING_SECRET=$(openssl rand -hex 24)
TOSS_SECRET_KEY=test_sk_$(openssl rand -hex 12)

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
# member 가 발급하는 액세스 토큰과 같은 모양(RS256 · typ=at+jwt · kid). member 에 준 그 개인키로 서명한다.
# 회원 로그인은 카카오만 있어 회원 토큰은 여기서 만든다. 관리자 토큰은 학습 중 실제 로그인으로 받는다.
mint_user_token() {
  local sub=$1 issued header payload signature
  issued=$(date +%s)
  header=$(printf '{"alg":"RS256","typ":"at+jwt","kid":"%s"}' "$KEY_ID" | b64url)
  payload=$(jq -nc --arg iss "$JWT_ISSUER" --arg aud "$JWT_AUDIENCE" --arg sub "$sub" --arg sid "$(python3 -c 'import uuid; print(uuid.uuid4())')" \
    --arg jti "$(python3 -c 'import uuid; print(uuid.uuid4())')" --argjson iat "$issued" \
    '{iss:$iss, aud:$aud, sub:$sub, sid:$sid, role:"USER", type:"ACCESS", jti:$jti, iat:$iat, exp:($iat + 7200)}' | b64url)
  signature=$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -sign "$WORK/jwt.pem" -binary | b64url)
  echo "$header.$payload.$signature"
}

# workload 가 읽는 값(/work/data.json). 회원은 시드의 회원마다 토큰 하나.
mkdir -p "$WORK/k6"
for customer in $CUSTOMERS; do
  jq -nc --arg c "$customer" --arg t "$(mint_user_token "$customer")" '{customerId:$c, token:$t}'
done | jq -s '.' > "$WORK/users.json"
write_workload_data() {
  jq -n --slurpfile users "$WORK/users.json" --slurpfile preorders "$1" \
    --arg password "$ADMIN_PASSWORD" --arg secret "$WAITING_SECRET" --arg category "$CATEGORY_ID" \
    --arg services "$WORKLOAD_SERVICES" --argjson port "$SERVER_PORT" --argjson lead "$OPEN_LEAD_SECONDS" --argjson steady "$STEADY_SECONDS" \
    '{users:$users[0], preorders:$preorders[0], adminPassword:$password, waitingSecret:$secret,
      categoryId:$category, services:($services | split(" ")), servicePort:$port, openLeadSeconds:$lead, steadySeconds:$steady}' > "$WORK/k6/data.json"
  chmod -R a+rX "$WORK/k6"
}

# 서비스 설정. 바탕은 그 서비스의 application.yml.example(운영과 같은 빈이 켜지게)이고, 여기서는 주소 · 포트 · 비밀값만 덮는다.
# Redis 시간 제한만 늘린다 — 러너 한 대에 JVM 여럿이 CPU 를 다투면 운영 값(수백 ms)을 넘겨 기동 · 요청이 실패한다. 캐시에 담길 클래스와는 무관하다.
app_json() {
  jq -n --arg s "$1" --argjson port "$SERVER_PORT" --argjson mport "$MANAGEMENT_PORT" --argjson toss "$TOSS_PORT" \
    --arg dbuser "$DB_USER" --arg dbpw "$DB_PASSWORD" --arg iss "$JWT_ISSUER" --arg aud "$JWT_AUDIENCE" \
    --rawfile pem "$WORK/jwt.pem" --arg kid "$KEY_ID" --arg hash "$ADMIN_HASH" --arg lead "PT${MIN_OPEN_LEAD_SECONDS}S" '
    def url($svc): "http://\($svc):\($port)";
    # 모든 서비스: 기반 시설 · 포트 · 토큰 발급자
    {
      "server.port": $port,
      "management.server.port": $mport,
      "spring.datasource.url": "jdbc:mysql://mysql:3306/shop?serverTimezone=UTC&characterEncoding=UTF-8",
      "spring.datasource.username": $dbuser,
      "spring.datasource.password": $dbpw,
      "spring.data.redis.host": "redis",
      "spring.data.redis.connect-timeout": "2s",
      "spring.data.redis.timeout": "2s",
      "nova.sqs.endpoint": "http://floci:4566",
      "jwt.issuer": $iss,
      "jwt.audience": $aud
    }
    # member 는 토큰을 서명하고, 나머지는 운영처럼 member 의 JWKS 로 공개키를 받아 검증한다
    + if $s == "member" then {"jwt.private-key": $pem, "jwt.key-id": $kid, "admin.password-hash": $hash}
      else {"jwt.jwk-set-uri": (url("member") + "/.well-known/jwks.json"), "jwt.jwk-set-allow-http": true} end
    # 서비스마다 부르는 곳
    + ({
        catalog: {"spring.http.serviceclient.order.base-url": url("order"),
                  "spring.http.serviceclient.member.base-url": url("member"),
                  "catalog.registration.min-open-lead": $lead},
        preorder: {"spring.http.serviceclient.catalog.base-url": url("catalog"),
                   "spring.http.serviceclient.order.base-url": url("order")},
        order: {"spring.http.serviceclient.preorder.base-url": url("preorder"),
                "spring.http.serviceclient.payment.base-url": url("payment"),
                "spring.http.serviceclient.payment-confirm.base-url": url("payment")},
        payment: {"spring.http.serviceclient.toss.base-url": "http://127.0.0.1:\($toss)"},
        waitingroom: {"waitingroom.relay.preorder-uri": url("preorder")},
        worker: {"spring.cloud.aws.sqs.endpoint": "http://floci:4566"},
        batch: {"external-mock.datasource.url": "jdbc:mysql://mysql:3306/external_mock?serverTimezone=UTC",
                "external-mock.datasource.username": $dbuser,
                "external-mock.datasource.password": $dbpw}
      }[$s] // {})'
}

# 서비스 컨테이너를 띄운다. 나머지 인자는 docker run 에 그대로 넘긴다(이미지 · 명령 포함).
#   app_container <이름> <서비스> <-d · --rm · 빈 값> docker run 인자…
app_container() {
  local name=$1 s=$2 how=$3
  shift 3
  docker run $how --name "$name" --label "$PROJECT" --network "$NETWORK" --network-alias "$s" \
    --cpus "$TRAIN_CPUS" --memory "$TRAIN_MEMORY" \
    -v "$ROOT/$s/src/main/resources/application.yml.example:/config/application.yml:ro" \
    -e SPRING_CONFIG_ADDITIONAL_LOCATION=file:/config/ \
    -e SPRING_APPLICATION_JSON="$(app_json "$s")" \
    -e WAITING_TOKEN_SECRET="$WAITING_SECRET" \
    -e TOSS_SECRET_KEY="$TOSS_SECRET_KEY" \
    "$@"
}

# 학습 실행. 이미지의 ENTRYPOINT 대신 java 를 직접 부른다 — 학습 옵션(-XX:AOTCacheOutput)을 붙여야 해서다.
#   run_app <이름> <이미지> <서비스> <-d · --rm · 빈 값> java 인자…
run_app() {
  local name=$1 image=$2 s=$3 how=$4
  shift 4
  app_container "$name" "$s" "$how" --entrypoint java "$image" "$@"
}

k6() {
  docker run --rm --label "$PROJECT" --network "$NETWORK" \
    -v "$HERE/workload:/scripts:ro" -v "$ROOT:/repo:ro" -v "$WORK/k6:/work:ro" \
    "$K6_IMAGE" run --quiet --no-usage-report "/scripts/$1"
}

fail_with_logs() {
  log "$1"
  shift
  for name in "$@"; do
    echo "── $name" >&2
    docker logs --tail 60 "$name" >&2 2>&1 || true
  done
  exit 1
}

collect_cache() {
  local s=$1 name=$2
  docker cp "$name:/tmp/app.aot" "$DIST/$s/app.aot" > /dev/null 2>&1 \
    || fail_with_logs "$s 의 AOT 캐시가 만들어지지 않았다" "$name"
  docker rm "$name" > /dev/null
}

# ── 3) 학습 ──────────────────────────────────────────────────────
train_startup() {
  local s=$1 name=$PROJECT-train-$1
  run_app "$name" "$PROJECT/$s:base" "$s" "" \
    -XX:AOTCacheOutput=/tmp/app.aot -Dspring.context.exit=onRefresh -jar /app/app.jar > /dev/null 2>&1 \
    || fail_with_logs "$s 학습 실패" "$name"
  collect_cache "$s" "$name"
}

# worker 대신: 대기 중인 등록 작업을 성공으로 넘기고 EXTERNAL_JOB_SUCCEEDED 를 보낸다(preorder 가 작업 행을 다시 읽어 REGISTERED 로 바꾼다).
# 외부 번호는 예약마다 달라야 한다(uq_preorder_external).
complete_register_jobs() {
  mysql_root shop -N -e "SELECT BIN_TO_UUID(j.id), p.preorder_token FROM preorder_sync_jobs j JOIN preorders p ON p.id = j.preorder_id
                         WHERE j.job_type = 'REGISTER' AND j.status = 'PENDING'" > "$WORK/jobs.tsv"
  local expected ids
  expected=$(wc -l < "$WORK/jobs.tsv" | tr -d ' ')
  [ "$expected" -gt 0 ] || fail_with_logs "넘길 등록 작업이 없다(1부의 예약 접수가 하나도 남지 않았다)" "$PROJECT-train-preorder"
  # 고른 작업만 바꾼다 — 그사이 생긴 작업이 이벤트 없이 SUCCEEDED 가 되면 그 예약은 REGISTERED 로 넘어가지 못한다
  ids=$(awk -F'\t' -v q="'" '{printf "%sUUID_TO_BIN(%s%s%s)", (NR > 1 ? "," : ""), q, $1, q}' "$WORK/jobs.tsv")
  mysql_root shop -e "UPDATE preorder_sync_jobs SET status = 'SUCCEEDED', updated_at = NOW(6) WHERE id IN ($ids)"
  mkdir -p "$WORK/sqs"
  awk -F'\t' '{print $1 "\t" $2}' "$WORK/jobs.tsv" | while IFS=$'\t' read -r job token; do
    jq -nc --arg job "$job" --arg token "$token" --arg id "$(python3 -c 'import uuid; print(uuid.uuid4())')" \
      --arg at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
      '{eventId:$id, eventType:"EXTERNAL_JOB_SUCCEEDED", aggregateType:"PREORDER_SYNC_JOB", aggregateId:$job, occurredAt:$at,
        payload:{syncJobId:$job, preorderId:$token, jobType:"REGISTER", externalNumber:("AOT-" + $job)}}'
  done | jq -s '[to_entries[] | {Id: (.key | tostring), MessageBody: (.value | tojson)}] | _nwise(10)' -c \
    | split -l 1 - "$WORK/sqs/batch-"
  chmod -R a+rX "$WORK/sqs"
  compose run --rm --no-deps -v "$WORK/sqs:/batches:ro" --entrypoint sh floci-init -c '
    set -e
    url=$(aws --endpoint-url http://floci:4566 sqs get-queue-url --queue-name preorder-events --query QueueUrl --output text)
    for f in /batches/batch-*; do aws --endpoint-url http://floci:4566 sqs send-message-batch --queue-url "$url" --entries "file://$f" > /dev/null; done' \
    > /dev/null
  local tokens
  tokens=$(awk -F'\t' -v q="'" '{printf "%s%s%s%s", (NR > 1 ? "," : ""), q, $2, q}' "$WORK/jobs.tsv")
  for _ in $(seq 1 60); do
    registered=$(mysql_root shop -N -e "SELECT COUNT(*) FROM preorders WHERE status = 'REGISTERED' AND preorder_token IN ($tokens)")
    [ "$registered" -ge "$expected" ] && break
    sleep 1
  done
  [ "$registered" -ge "$expected" ] || fail_with_logs "예약이 REGISTERED 로 넘어가지 않았다($registered/$expected)" "$PROJECT-train-preorder"
  log "예약 $registered 건 REGISTERED"
  # 2부가 주문할 예약 — 회원 토큰과 짝짓도록 users.json 에서의 회원 위치를 함께 넘긴다
  mysql_root shop -N -e "SELECT preorder_token, BIN_TO_UUID(customer_id) FROM preorders WHERE status = 'REGISTERED' ORDER BY created_at" \
    | jq -R -s -c --slurpfile users "$WORK/users.json" '($users[0] | map(.customerId)) as $ids
        | [split("\n")[] | select(length > 0) | split("\t") as [$token, $customer] | {token: $token, userIndex: ($ids | index($customer))}]' > "$WORK/preorders.json"
  jq -e 'all(.userIndex != null)' "$WORK/preorders.json" > /dev/null || fail_with_logs "토큰이 없는 회원의 예약이 있다" "$PROJECT-train-preorder"
}

#   wait_ready <서비스…> — 관리 포트 readiness 가 모두 UP 일 때까지(최대 180초)
wait_ready() {
  local checks="" s names=()
  for s in "$@"; do
    checks="$checks http://$s:$MANAGEMENT_PORT/actuator/health/readiness"
    names+=("$PROJECT-train-$s")
  done
  docker run --rm --label "$PROJECT" --network "$NETWORK" --entrypoint sh "$CURL_IMAGE" -c "
    for i in \$(seq 1 180); do
      ok=1; for u in $checks; do curl -sf -o /dev/null \"\$u\" || ok=0; done
      [ \$ok = 1 ] && exit 0; sleep 1
    done; exit 1" || fail_with_logs "준비되지 않았다: $*" "${names[@]}"
}

train_workload() {
  local names=() s
  # 하나씩 띄워 준비될 때까지 기다린다. member 가 맨 앞이다 — 나머지는 기동하자마자 member 의 JWKS 를 받는다
  # (waitingroom 은 놓치면 5분 뒤에야 다시 받는다). 한꺼번에 띄우면 CPU 경합으로 기동 시간 제한을 넘기기도 한다.
  for s in $WORKLOAD_SERVICES; do
    run_app "$PROJECT-train-$s" "$PROJECT/$s:base" "$s" -d -XX:AOTCacheOutput=/tmp/app.aot -jar /app/app.jar > /dev/null
    names+=("$PROJECT-train-$s")
    wait_ready "$s"
  done
  # Toss 스텁은 payment 의 네트워크를 함께 쓴다 — payment 는 Toss 주소로 https 나 loopback 만 받는다
  docker run -d --name "$PROJECT-toss" --label "$PROJECT" --network "container:$PROJECT-train-payment" \
    -v "$HERE/toss/mappings:/home/wiremock/mappings:ro" "$TOSS_IMAGE" --port "$TOSS_PORT" --global-response-templating --disable-http2-plain > /dev/null
  log "요청 학습 1부(상품 등록 · 예약 접수)"
  echo '[]' > "$WORK/none.json"
  write_workload_data "$WORK/none.json"
  k6 prepare.js || fail_with_logs "요청 학습 1부 실패" "${names[@]}"
  complete_register_jobs
  log "요청 학습 2부(${STEADY_SECONDS}초)"
  write_workload_data "$WORK/preorders.json"
  k6 steady.js || fail_with_logs "요청 학습 2부 실패" "${names[@]}"

  # 정상 종료(SIGTERM)에서 캐시가 쓰인다. 종료 처리 · 캐시 조립에 시간이 걸려 넉넉히 기다린다
  docker rm -f "$PROJECT-toss" > /dev/null
  for name in "${names[@]}"; do docker stop -t 180 "$name" > /dev/null & done
  wait
  for s in $WORKLOAD_SERVICES; do
    collect_cache "$s" "$PROJECT-train-$s"
  done
}

log "요청 학습: $WORKLOAD_SERVICES"
train_workload
for s in "${services[@]}"; do
  if [ "$(mode_of "$s")" = startup ]; then
    log "$s 기동 학습"
    train_startup "$s"
  fi
done

# ── 4) 최종 이미지 · 5) 검증 ─────────────────────────────────────
# 이미지를 그 ENTRYPOINT 그대로 띄워 onRefresh 까지 걸린 벽시계 시간(컨테이너 시작 포함 — 두 이미지에 같은 몫).
# 옵션은 JDK_JAVA_OPTIONS 로 더한다(java 실행기가 명령줄 앞에 붙인다). 최종 이미지에 AOTMode=on 을 주면 ENTRYPOINT 의
# 캐시 경로가 틀렸을 때 여기서 실패한다.
#   startup_seconds <이미지> <서비스> [더할 JVM 옵션]
startup_seconds() {
  local image=$1 s=$2 extra=${3:-} start
  start=$(now)
  app_container "$PROJECT-measure-$s" "$s" --rm \
    -e JDK_JAVA_OPTIONS="$extra -Dspring.context.exit=onRefresh" "$image" \
    > "$WORK/$s.measure.log" 2>&1 || { log "$s 측정 실행 실패($image $extra)"; tail -n 60 "$WORK/$s.measure.log" >&2; return 1; }
  python3 -c "print(f'{$(now) - $start:.1f}')"
}

mb() { python3 -c "print(f'{$1 / 1048576:.0f}')"; }

{
  echo "## AOT 캐시"
  echo "베이스 \`$BASE_IMAGE\` · 측정 자원 cpu $TRAIN_CPUS / 메모리 $TRAIN_MEMORY · 기동은 빈 생성 완료(onRefresh)까지"
  echo
  echo "| 서비스 | 학습 | 기동(캐시 없음) | 기동(캐시) | 단축 | app.aot | 이미지 증가 |"
  echo "|---|---|---|---|---|---|---|"
} >> "$SUMMARY"

for s in "${services[@]}"; do
  docker build -q -f "$ROOT/docker/Dockerfile.aot" --build-arg TRAINED_IMAGE="$PROJECT/$s:base" \
    --build-arg AOT_FILE="dist/$s/app.aot" -t "$FINAL_REPO:$s" "$ROOT" > /dev/null
  # 최종 이미지는 ENTRYPOINT 에 AOTMode=on 만 더한다 — 캐시를 받지 못하면 여기서 실패한다.
  # 기준은 기반 이미지 그대로다(JDK 기본 CDS 는 켜진 지금의 운영과 같다). AOTMode=off 는 기본 CDS 까지 꺼 효과를 부풀린다.
  with=$(startup_seconds "$FINAL_REPO:$s" "$s" -XX:AOTMode=on)
  without=$(startup_seconds "$PROJECT/$s:base" "$s")
  aot=$(wc -c < "$DIST/$s/app.aot" | tr -d ' ')
  grown=$(( $(docker image inspect -f '{{.Size}}' "$FINAL_REPO:$s") - $(docker image inspect -f '{{.Size}}' "$PROJECT/$s:base") ))
  cut=$(python3 -c "print(f'{(1 - $with / $without) * 100:.0f}%')")
  echo "| $s | $(mode_of "$s") | ${without}s | ${with}s | $cut | $(mb "$aot") MB | $(mb "$grown") MB |" >> "$SUMMARY"
  log "$s 검증 통과 — ${without}s → ${with}s"
done
