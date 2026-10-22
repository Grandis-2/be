# 대기열 입장 속도 운영값 (대기열 → preorder)

관리자가 대기열 관리자 API(`PUT /api/v1/admin/waitingroom/admission-rate`)로 정한 전역 입장 속도를 preorder 가 증설 기준으로 읽는다. 대기열의 실제 입장 속도는 이 값과 preorder 용량 합(`preorder-capacity-heartbeat.md`) 중 작은 쪽이라, 관리자가 올린 만큼 preorder 가 늘어야 입장도 따라간다. 문서와 어긋나는 쪽이 고친다.

## 위치

| 항목 | 값 |
| --- | --- |
| Redis | 대기열이 쓰는 `spring.data.redis`(지금은 모든 서비스가 같은 클러스터) |
| 키 | `wr:settings` (해시 하나) |
| 필드 | `global-credit` |
| 값 | 0 이상 정수 문자열. 전 상품 합산 초당 입장 인원. 0 은 입장을 멈춘 것이다 |

- 대기열만 쓰고 지운다. preorder 는 읽기만 한다.
- 브레이크(접수 전달이 나쁠 때 잠시 줄이는 배율)와 용량 상한을 적용하기 **전** 값이다. 증설 기준으로는 이 값을 쓴다 — 줄인 값으로 정하면 장애가 풀린 뒤 모자란다.
- 필드가 없거나 정수가 아니면 관리자가 정한 적이 없는 것이다. 대기열은 이때 배포 설정(`waitingroom.admission.global-credit`)을 쓴다. preorder 는 이 값을 모르므로 "운영값 없음" 으로 다룬다.
- 대기열은 운영값을 `waitingroom.admission.max-global-credit` 이하로만 받는다.

같은 해시의 다른 필드(`max-wait-sec` · `leave-grace-sec` · `cap:{상품}`)는 대기열 내부 값이다. 쓰려면 이 문서에 먼저 더한다.
