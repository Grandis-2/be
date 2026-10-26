# 회차 일정에 맞춘 최소 대수 (preorder → 인프라)

preorder 가 회차 일정 · 예상 방문자 수 · 관리자 입장 속도를 보고 대기열 · preorder ECS 서비스의 **최소 대수**를 바꾼다. 오픈 순간 몰리는 요청을 CPU 오토스케일링이 늦게 따라가는 것을 막으려는 것이다. 문서와 어긋나는 쪽이 고친다.

## 동작

- preorder 태스크마다 1분마다(`nova.scaling.interval`), 그리고 관리자가 회차를 바꾸면 커밋 뒤 바로 한 번 다시 계산한다. 매번 지금 상태 전체로 계산하므로 놓친 변경 · 실패한 호출은 다음 주기에 맞춰진다.
- 맞추기는 태스크 안 전용 스레드 하나에서 돌고, 태스크 사이에서는 공유 Redis 잠금(`preorder:scaling:lock`)으로 한 번에 하나다 — 먼저 읽은 태스크의 늦은 호출이 나중에 읽은 값을 덮지 않는다. AWS 호출은 5초에서 끊는다.
- 대수를 직접 정하지 않는다. Application Auto Scaling 대상의 `MinCapacity` 만 바꾼다 — 지금 대수가 그보다 적으면 AWS 가 늘리고, 이미 많으면 그대로 둔다. 최소를 내리면 기존 오토스케일링 정책(CPU · 요청 수)이 부하를 보고 줄인다.
- 지금 값과 같으면 부르지 않는다(`DescribeScalableTargets` 로 보고 다를 때만 `RegisterScalableTarget`).
- 꺼져 있으면(기본) AWS 를 부르지 않고 계산한 값이 바뀔 때 로그(`최소 대수를 바꿀 때다`)만 남긴다.

## 계산

걸린 회차는 회원에게 공개되고 판매 중지가 아니며, 마감 전이고 오픈이 `지금 − 30분 < opensAt ≤ 지금 + 15분` 인 회차다(`hold-after-open` · `lead-time`). 오픈 뒤 30분이 지나면 오토스케일링에 맡긴다.

| 서비스 | 걸린 회차가 없을 때 | 있을 때 |
| --- | --- | --- |
| waitingroom | `baseline` | `max(surge, ⌈예상 방문자 ÷ visitors-per-task⌉)` 를 걸린 회차 중 가장 큰 값. `surge`(기본 3)는 예상 방문자 수가 없는 회차에도 올리는 대수다 |
| preorder | `baseline` | `max(surge, ⌈global-credit ÷ accept-per-second⌉)`. `surge`(기본 3)는 같은 회차의 접수가 회차 행 잠금 하나에 줄 서서 그 이상 늘려도 한 회차의 접수가 빨라지지 않는 대수다 |

- `global-credit` 은 대기열 관리자가 정한 전체 초당 입장 인원이다. 공유 Redis 의 `wr:settings` 해시에서 읽기만 한다(쓰기 · 지우기는 대기열). 대기열의 입장 속도가 살아 있는 preorder 용량의 합에 묶이므로, 관리자가 올리면 그만큼 받을 대수로 올린다. `accept-per-second` 는 `nova.capacity.heartbeat.accept-per-second`(기본 100)로, 대기열이 더하는 태스크당 용량과 같은 값이다.
  - 걸린 회차가 있을 때만 읽는다. 회차가 없는 동안 미리 올려 둬도 늘리지 않는다.
  - 없거나 0 이상 정수가 아니면 `surge` 만 쓴다. Redis 를 읽지 못하면 그 주기는 `surge` 로 맞추고 `preorder.scaling.failures` 에 센다.
  - 관리자가 내리면 최소도 내려가고, 실제로 줄이는 것은 오토스케일링 정책이 한다.
- 회차마다 따로 걸지 않고 가장 큰 값 하나로 맞추므로, 겹친 회차 하나가 끝나도 다른 회차 몫이 내려가지 않는다.
- `max` 를 넘으면 `max` 로 자르고 WARN 을 남긴다. 최소를 AWS 대상의 최대보다 높이면 거절되므로 `nova.scaling.*.max` 는 Terraform 의 max 와 같게 둔다.
- `visitors-per-task`(기본 5,000)는 측정 전 임시값이다. 대기열 태스크 하나의 처리량을 재면 바꾼다.

## 인프라가 할 일

1. **preorder 태스크 역할 권한.** `application-autoscaling:DescribeScalableTargets` · `application-autoscaling:RegisterScalableTarget`, 그리고 ECS 대상 등록에 함께 필요한 `ecs:DescribeServices` · `ecs:UpdateService`(대기열 · preorder 두 서비스로 한정). Application Auto Scaling 쪽을 대상 단위로 좁힐 수 있는지(대상 ARN · 태그 조건)는 인프라가 정한다.
2. **Terraform 이 최소 대수를 되돌리지 않게.** ECS 서비스 모듈의 `aws_appautoscaling_target` 에 `lifecycle { ignore_changes = [min_capacity] }`. 없으면 다음 apply 가 preorder 가 올린 최소를 평소 값으로 되돌린다.
3. **최대 대수.** 지금 두 서비스 모두 5대다. 예상 방문자에 맞추려면 대기열 max 를 올리고, 같은 값을 `nova.scaling.waitingroom.max` 에 넣는다.
4. **preorder 설정.** 운영 설정이 들어가는 `terraform/application/preorder/application.yml` 에 넣는다. 클러스터 · 서비스 이름은 환경마다 달라 기본값이 없고, 켤 때 셋 다 넣어야 기동한다. `baseline` · `max` 는 Terraform 값과 같게 둔다.

   ```yaml
   nova:
     scaling:
       enabled: true
       cluster: nova-prod
       waitingroom: { service: nova-prod-waitingroom, baseline: 1, max: 5 }
       preorder: { service: nova-prod-preorder, baseline: 1, max: 5 }
   ```
5. **켜는 순서.** 1 → 2 → 3 을 먼저 반영하고 4 로 켠다. 켜기 전에도 꺼진 상태의 로그로 계산을 미리 볼 수 있다.

## 지표

| 이름 | 뜻 |
| --- | --- |
| `preorder.scaling.floor{service}` | 마지막으로 계산한 최소 대수 |
| `preorder.scaling.failures` | 회차 조회 · 관리자 입장 속도 읽기 · AWS 호출 실패 수. 실패해도 접수에는 영향이 없고 다음 주기에 다시 맞춘다 |
| `preorder.scaling.admission-target` | 마지막 계산에 쓴 `global-credit`. 걸린 회차가 없거나 값이 없으면(읽지 못한 때 포함) 0 |
