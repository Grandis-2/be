package com.grandis.nova.order.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 기록만 한다. 발행 완료 · 실패 표시와 릴레이 조회는 발행기를 붙일 때 더한다.
 *
 * common:outbox 이전 시: preorder 의 저장소(public, markPublished · recordFailure · lockUnpublished)와 한 벌로 합친다.
 */
interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
}
