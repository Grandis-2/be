package com.grandis.nova.order.order.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.util.UUID;

/**
 * orders 의 가상 칼럼 ship_to_phone_last4(받는 분 전화번호의 숫자 뒤 4자리)를 조건으로만 쓰는 읽기 전용 매핑. 관리자 검색이 서브쿼리로만 쓴다.
 *
 * {@link OrderJpaEntity} 에 매핑하지 않는다 — 가상 칼럼은 색인 밖에서 읽을 때마다 정규식을 계산하는데, 엔티티는 매핑한 칼럼을 모두 읽으므로
 * 결제 · 배송 · 취소의 주문 읽기마다 그 계산이 붙는다. 여기서는 색인에 든 칼럼(가상 칼럼 · id)만 읽어 계산하지 않는다.
 */
@Entity
@Immutable
@Table(name = "orders")
public class OrderPhoneLast4JpaEntity {

    @Id
    private UUID id;

    @Column(name = "ship_to_phone_last4", insertable = false, updatable = false, length = 4)
    private String shipToPhoneLast4;

    protected OrderPhoneLast4JpaEntity() {
    }
}
