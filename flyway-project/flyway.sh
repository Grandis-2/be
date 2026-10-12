#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
: "${FLYWAY_URL:?FLYWAY_URL을 설정하세요 (jdbc:mysql://.../shop).}"
: "${FLYWAY_USER:?FLYWAY_USER를 설정하세요.}"
: "${FLYWAY_PASSWORD:?FLYWAY_PASSWORD를 설정하세요.}"

# 이미지가 amd64 전용이므로 Apple Silicon에서도 같은 버전을 실행한다. 운영 적용 이미지(Dockerfile)와 같은 digest를 쓴다.
exec docker run --rm --platform linux/amd64 \
  --network "${NOVA_FLYWAY_NETWORK:-bridge}" \
  --mount "type=bind,source=$project_dir,target=/flyway/project,readonly" \
  --workdir /flyway/project \
  -e FLYWAY_URL -e FLYWAY_USER -e FLYWAY_PASSWORD \
  redgate/flyway:11.20.0@sha256:c92844730f0cccd8f8c34997dfe6be106034df0a1faa186029ee554847b57be0 \
  -configFiles=/flyway/project/flyway.toml "$@"
