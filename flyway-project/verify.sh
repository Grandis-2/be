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

# 컨테이너의 mysql 클라이언트는 기본이 latin1 이다. 한글을 ? 로 바꿔 비교가 뭉개지고 JSON_TABLE 의 문자열 칸이 깨지므로 utf8mb4 로 붙는다
mysql_query() {
  docker exec -e MYSQL_PWD=nova-ci-only "$container_id" \
    mysql -uroot --batch --skip-column-names --default-character-set=utf8mb4 -e "$1"
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

# release 가 운영 DB 에 돌리는 이미지(Dockerfile)를 같은 DB 에 한 번 더 돌린다. 이미지에 든 마이그레이션 · 설정이
# 위와 같으면 적용할 것 없이 끝나고 이력도 그대로다. 베이스 Flyway 는 flyway.sh 와 같은 digest 여야 한다
flyway_image() { grep -o 'redgate/flyway:[^ ]*@sha256:[0-9a-f]*' "$1"; }
if [[ "$(flyway_image "$project_dir/Dockerfile")" != "$(flyway_image "$project_dir/flyway.sh")" ]]; then
  echo 'Dockerfile 과 flyway.sh 의 Flyway 이미지가 다르다. 둘을 함께 바꾼다.' >&2
  exit 1
fi
image_id="$(docker build --platform linux/amd64 -q "$project_dir")"
docker run --rm --platform linux/amd64 --network "container:$container_id" \
  -e FLYWAY_URL -e FLYWAY_USER -e FLYWAY_PASSWORD "$image_id"
docker rmi -f "$image_id" >/dev/null
[[ "$history_before" == "$(mysql_query 'SELECT installed_rank, version, checksum, success FROM shop.flyway_schema_history ORDER BY installed_rank')" ]]

# 운영 초기 데이터(init.sql)를 최신 정의에 두 번 적용해 행이 같은지 본다(updated_at 은 적용 시각이라 뺀다).
# 처음은 README 처럼 문자셋 없이(이 클라이언트는 latin1), 다음은 utf8mb4 로 붙는다 — init.sql 이 SET NAMES 를 빠뜨리면 한글이 달라진다.
# 트리 검사는 CategoryTreeService 가 말없이 걸러 내는 것들이다: 부모에게 또 부모가 있는 행(깊이 3 이상 · 순환)은 응답에서 빠지고,
# key · label 이 없는 축 원소가 있으면 그 카테고리의 축은 [] 가 된다
fail() { echo "$1" >&2; exit 1; }
apply_init() { docker exec -i -e MYSQL_PWD=nova-ci-only "$container_id" mysql -uroot "$@" < "$project_dir/init.sql"; }
init_rows() {
  mysql_query 'SELECT BIN_TO_UUID(id), BIN_TO_UUID(parent_id), name, sort_order, option_axes, created_at FROM shop.categories ORDER BY id'
}
apply_init
init_first="$(init_rows)"
[[ -n "$init_first" ]] || fail 'init.sql 을 적용했는데 카테고리가 없다.'
apply_init --default-character-set=utf8mb4
[[ "$init_first" == "$(init_rows)" ]] || fail 'init.sql 을 다시 적용하니 카테고리 행이 바뀌었다(고정되지 않은 값이나 SET NAMES 누락).'
[[ "$(mysql_query 'SELECT COUNT(*) FROM shop.categories c JOIN shop.categories p ON c.parent_id = p.id
  WHERE p.parent_id IS NOT NULL')" == 0 ]] || fail '깊이 3 이상이거나 순환하는 카테고리가 있다.'
[[ "$(mysql_query "SELECT COUNT(*) FROM shop.categories c,
  JSON_TABLE(c.option_axes, '\$[*]' COLUMNS (axis_key VARCHAR(255) PATH '\$.key', axis_label VARCHAR(255) PATH '\$.label')) axis
  WHERE COALESCE(TRIM(axis.axis_key), '') = '' OR COALESCE(TRIM(axis.axis_label), '') = ''")" == 0 ]] ||
  fail 'key · label 이 없는 기본 옵션 축 원소가 있다.'

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
echo '신규 DB 적용 · validate · 재실행 이력 불변 · Mock 미생성 · 운영 적용 이미지 · 초기 데이터(init.sql) 검사 통과. 최신 shop 정의: build/flyway/schema.sql'
