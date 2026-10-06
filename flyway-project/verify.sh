#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$project_dir/.." && pwd)"
if [[ $# -gt 0 ]]; then
  echo '사용법: bash flyway-project/verify.sh' >&2
  exit 1
fi
python3 "$project_dir/migrations.py" check
mkdir -p "$repo_dir/build/flyway"

# 호스트 포트/볼륨 없이 이번 검사만의 빈 MySQL을 사용한다.
container_id="$(docker run -d --rm \
  -e MYSQL_ROOT_PASSWORD=nova-ci-only \
  -e MYSQL_DATABASE=shop \
  -e MYSQL_USER=nova_migrator \
  -e MYSQL_PASSWORD=nova-ci-only \
  mysql:8.4.11 \
  --character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci)"
trap 'docker rm -f "$container_id" >/dev/null 2>&1 || true' EXIT

mysql_query() {
  docker exec -e MYSQL_PWD=nova-ci-only "$container_id" \
    mysql -uroot --batch --skip-column-names -e "$1"
}
ready=false
for ((attempt=0; attempt<90; attempt++)); do
  if docker exec -e MYSQL_PWD=nova-ci-only "$container_id" \
    mysql --protocol=TCP -h127.0.0.1 -unova_migrator shop -e 'SELECT 1' >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 2
done
if [[ "$ready" != true ]]; then
  docker logs "$container_id" >&2
  exit 1
fi

export NOVA_FLYWAY_NETWORK="container:$container_id"
export FLYWAY_URL='jdbc:mysql://127.0.0.1:3306/shop?allowPublicKeyRetrieval=true&sslMode=DISABLED'
export FLYWAY_USER=nova_migrator FLYWAY_PASSWORD=nova-ci-only
bash "$project_dir/flyway.sh" migrate
bash "$project_dir/flyway.sh" validate
history_before="$(mysql_query 'SELECT installed_rank, version, checksum, success FROM shop.flyway_schema_history ORDER BY installed_rank')"
bash "$project_dir/flyway.sh" migrate
history_after="$(mysql_query 'SELECT installed_rank, version, checksum, success FROM shop.flyway_schema_history ORDER BY installed_rank')"
[[ "$history_before" == "$history_after" ]]
[[ "$(mysql_query "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = 'external_mock'")" == 0 ]]

# 시간・데이터・Flyway 이력을 제외한 최신 shop 정의를 덤프한다. 저장소에는 두지 않고 산출물로만 남긴다(CI: shop-schema).
snapshot="$repo_dir/build/flyway/schema.sql"
{
  printf '%s\n' \
    '-- 자동 생성: bash flyway-project/verify.sh' \
    '-- 정본: flyway-project/migrations/*.sql (이 파일은 직접 수정하지 않는다.)' \
    '-- MySQL 8.4 / shop 전용. external_mock은 Mock 저장소가 관리한다.' \
    'CREATE DATABASE IF NOT EXISTS shop DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;' \
    'USE shop;'
  docker exec -e MYSQL_PWD=nova-ci-only "$container_id" \
    mysqldump -uroot --no-data --skip-comments --skip-dump-date \
    --set-gtid-purged=OFF --no-tablespaces --skip-add-drop-table \
    --skip-lock-tables --ignore-table=shop.flyway_schema_history shop | sed '${/^$/d;}'
} > "$snapshot"
echo '신규 DB 적용 · validate · 재실행 이력 불변 · Mock 미생성 검사 통과. 최신 shop 정의: build/flyway/schema.sql'
