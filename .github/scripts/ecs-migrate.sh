#!/usr/bin/env bash
# 운영 DB 에 마이그레이션을 적용한다. Flyway 태스크를 ECS 에서 한 번 띄우고 끝날 때까지 기다린다.
# DB(NCP)는 AWS NAT 고정 IP 만 받아 러너에서는 닿지 않으므로 클러스터 안에서 돈다.
# 띄울 태스크 정의 · 서브넷 · SG 는 인프라가 SSM 파라미터 /<클러스터>/migration/run-task 에 적는다 (nova-infra ecs 스택 migration.tf).
#
# 사용: ECS_CLUSTER=nova-prod ecs-migrate.sh check | run
#   check — 띄울 수 있는지 본다. 클러스터가 없으면(ECS 스택 전 · 내린 뒤) 경고하고 skip=true 를 낸다.
#           클러스터가 있는데 파라미터가 없으면 실패한다 — 마이그레이션 없이 배포하면 새 코드가 표 · 칸을 찾지 못한다
#   run   — 태스크를 띄우고 기다린다. 태스크가 뜨지 못하거나 Flyway 가 0 이 아닌 값으로 끝나면 실패한다
set -euo pipefail
cluster=${ECS_CLUSTER:?ECS_CLUSTER 가 없다}
summary=${GITHUB_STEP_SUMMARY:-/dev/null}
param_name="/$cluster/migration/run-task"

# 파라미터를 param 에 읽는다. 없거나 다른 클러스터 것이면 원인을 남기고 끝낸다
read_param() {
  if ! param=$(aws ssm get-parameter --name "$param_name" --query Parameter.Value --output text 2>&1); then
    echo "::error::$param_name 를 읽지 못했다 — nova-infra 의 마이그레이션 변경(ecr · securitygroup · iam · ecs)을 먼저 apply 한다: $param"
    exit 1
  fi
  if [ "$(jq -r .cluster <<< "$param")" != "$cluster" ]; then
    echo "::error::$param_name 의 클러스터($(jq -r .cluster <<< "$param"))가 $cluster 와 다르다"
    exit 1
  fi
}

check() {
  active=$(aws ecs describe-clusters --clusters "$cluster" \
    --query "clusters[?status=='ACTIVE'] | length(@)" --output text)
  if [ "$active" != 1 ]; then
    echo "::warning::$cluster 클러스터가 없어 마이그레이션을 건너뜀"
    echo "- $cluster 클러스터가 없어 마이그레이션을 건너뜀" >> "$summary"
    echo "skip=true" >> "${GITHUB_OUTPUT:-/dev/null}"
    return 0
  fi
  read_param
  echo "skip=false" >> "${GITHUB_OUTPUT:-/dev/null}"
}

run() {
  read_param
  task_def=$(jq -r .taskDefinition <<< "$param")
  container=$(jq -r .container <<< "$param")
  log_group=$(jq -r .logGroup <<< "$param")
  log_prefix=$(jq -r .logStreamPrefix <<< "$param")
  network=$(jq -c '{awsvpcConfiguration: {subnets, securityGroups, assignPublicIp: "DISABLED"}}' <<< "$param")

  started=$(aws ecs run-task --cluster "$cluster" --task-definition "$task_def" --launch-type FARGATE --count 1 \
    --started-by "release-${GITHUB_RUN_ID:-manual}" --network-configuration "$network" --output json)
  if [ "$(jq '.failures | length' <<< "$started")" -ne 0 ] || [ "$(jq '.tasks | length' <<< "$started")" -ne 1 ]; then
    echo "::error::마이그레이션 태스크를 띄우지 못했다: $(jq -c .failures <<< "$started")"
    exit 1
  fi
  task=$(jq -r '.tasks[0].taskArn' <<< "$started")
  id=${task##*/}
  echo "마이그레이션 태스크 시작: $id ($task_def)"

  # 대기 한 번은 6초 간격 100번(10분)이다. 세 번까지 다시 기다린다. 권한 · 자격 오류는 바로 끝나므로 마지막 오류를 남긴다
  stopped=false
  wait_err=""
  for _ in 1 2 3; do
    if wait_err=$(aws ecs wait tasks-stopped --cluster "$cluster" --tasks "$task" 2>&1); then
      stopped=true
      break
    fi
  done

  # Flyway 출력을 이 로그로 옮긴다. 태스크가 뜨지 못했으면 스트림이 없다
  if logs=$(aws logs get-log-events --log-group-name "$log_group" --log-stream-name "$log_prefix/$container/$id" \
      --start-from-head --query 'events[].message' --output text 2>&1); then
    tr '\t' '\n' <<< "$logs"
  else
    echo "Flyway 로그를 읽지 못했다: $logs"
  fi

  if [ "$stopped" != true ]; then
    echo "::error::마이그레이션이 끝난 것을 확인하지 못했다: $id — $wait_err (태스크가 아직 돌 수 있다. 콘솔에서 확인한다)"
    exit 1
  fi
  desc=$(aws ecs describe-tasks --cluster "$cluster" --tasks "$task" --output json)
  code=$(jq -r --arg c "$container" '(.tasks[0].containers // [])[] | select(.name == $c) | .exitCode // "없음"' <<< "$desc")
  code=${code:-없음}
  if [ "$code" != 0 ]; then
    echo "::error::마이그레이션 실패: $id 종료 코드 $code — $(jq -r '.tasks[0].stoppedReason // "" ' <<< "$desc")"
    echo "- 마이그레이션 실패: $id (종료 코드 $code)" >> "$summary"
    exit 1
  fi
  echo "- 마이그레이션 적용: $id" >> "$summary"
}

case "${1:-}" in
  check) check ;;
  run) run ;;
  *) echo "사용: ECS_CLUSTER=<클러스터> $0 check | run" >&2; exit 2 ;;
esac
