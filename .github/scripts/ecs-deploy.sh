#!/usr/bin/env bash
# 받은 서비스를 ECS 에 다시 배포한다(force-new-deployment). 태스크 정의는 인프라가 관리하므로 latest 를 다시 받게만 한다.
# 시작만 하고 기다리지 않는다 — 배포 진행은 ECS 가 한다.
# 클러스터가 아직 없거나(ECS 스택 전) 아직 만들지 않은 서비스는 건너뛴다. 클러스터가 있는데 서비스가 없으면 알림을 남긴다 —
# 서비스 이름 규칙(<클러스터>-<서비스>)이 어긋나면 배포 0건이 초록으로 지나가기 때문이다.
#
# 사용: ECS_CLUSTER=nova-prod ecs-deploy.sh <서비스>...   (서비스가 없으면 아무것도 하지 않는다)
set -euo pipefail

cluster=${ECS_CLUSTER:?ECS_CLUSTER 가 없다}
summary=${GITHUB_STEP_SUMMARY:-/dev/null}
[ "$#" -gt 0 ] || exit 0

# 클러스터가 없으면 describe-services 가 빈 답이 아니라 ClusterNotFoundException 으로 실패한다. 먼저 본다
active=$(aws ecs describe-clusters --clusters "$cluster" \
  --query "clusters[?status=='ACTIVE'] | length(@)" --output text)
if [ "$active" != 1 ]; then
  echo "- $cluster 클러스터가 없어 배포를 건너뜀: $*" | tee -a "$summary"
  exit 0
fi

for s in "$@"; do
  svc="$cluster-$s"
  status=$(aws ecs describe-services --cluster "$cluster" --services "$svc" \
    --query 'services[0].status' --output text)
  if [ "$status" != ACTIVE ]; then
    echo "::notice::$svc 가 ECS 에 없어 배포를 건너뜀"
    echo "- $svc: ECS 에 없어 건너뜀" >> "$summary"
    continue
  fi
  aws ecs update-service --cluster "$cluster" --service "$svc" \
    --force-new-deployment --query 'service.serviceName' --output text
  echo "- $svc 배포 시작" >> "$summary"
done
