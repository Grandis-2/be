package com.grandis.nova.payment.deadletter.application;

import java.time.Instant;
import java.util.UUID;

/**
 * 일괄 되돌리기 진행 — 칸 뜻의 정본이다. 칸은 preorder BatchReprocessProgress 와 같다(관리자 화면이 같다). 결과 수는 센 값이 아니라
 * 조회할 때 큐로 보낸 원래 행의 지금 상태다 — 결과는 되돌린 메시지를 처리한 뒤에 난다. 다시 실패해 새로 쌓인 DLQ 행은 세지 않는다.
 * 진행은 Redis 에 하루 둔다. 시작 기록을 남기지 못한 일괄은 보내는 중이어도 조회가 404 다. 대상 하나의 진행 기록(다룬 수와 보낸 표시를
 * 스크립트 하나로 함께 남긴다)을 잃으면 그 대상은 sent · unsent 어디에도 들지 않고 pending 에 남는다 — finishedAt 이 찍혔는데 pending 이
 * 남을 수 있다.
 *
 * @param sent           큐로 보낸 수
 * @param unsent         다뤘지만 그 사이 상태가 바뀌었거나 보내기 결과를 남기지 못한 수(결과로 세지 않는다). 시간 초과처럼 실제로는 큐에
 *                       들어갔을 수 있는 행도 여기 든다
 * @param succeeded      보낸 뒤 처리된(SUCCEEDED) 수. 환불을 열었다는 뜻은 아니다
 * @param failed         보낸 뒤 또 DLQ 로 온(REDRIVE_FAILED) 수
 * @param canceled       결제 DLQ 는 보낸 행을 버리는 길이 없어 늘 0 이다
 * @param pending        아직 보내지 않았거나 결과를 기다리는 수
 * @param lastProgressAt 마지막으로 대상을 다룬 시각. 끝나지 않았는데 오래 그대로면 보내던 인스턴스가 멈춘 것이다(같은 요청을 다시 보내면
 *                       이어진다)
 * @param finishedAt     모두 다룬 시각. 아직이면 null
 */
public record BatchRedriveProgress(UUID batchId, int targetCount, int skippedCount, int sent, int unsent,
                                   int succeeded, int failed, int canceled, int pending, Instant startedAt,
                                   Instant lastProgressAt, Instant finishedAt) {
}
