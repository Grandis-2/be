-- preorder_payment_anomalies — 취소가 끝난 예약에 온 결제 확인(preorder 소유). 이상 결제 하나가 한 행이다.
--
-- 왜: order 는 취소한 주문의 승인을 반영하지 않는다(contracts/preorder-order-events.md "보장"). 그래도 오면 돈이 나갔을 수 있어
--     ERROR 로그와 지표(preorder.payment.after_cancel)로 알린다. 같은 결제 확인은 다시 · 겹쳐 올 수 있다(SQS 중복 전달 · 아웃박스 재발행) —
--     이 행이 처음 들어갈 때만 알려 한 결제가 한 번만 세진다. 운영자는 이 표로 이상 결제 목록을 본다.
-- PK (preorder_id, paid_at): 같은 예약의 같은 결제 시각은 같은 결제다. 이벤트 id 가 아니라 결제 시각으로 가른다 — 같은 결제를 새 이벤트로 다시
--   보내도 한 행이다. paid_at 은 마이크로초로 잘라 넣는다(preorders.reserved_at 과 같은 정밀도).
-- order_id: 받은 메시지의 주문 id. 확인용이라 형식을 가정하지 않는다.
-- 외래 키: 예약(같은 서비스)만 건다. 넣는 트랜잭션이 이미 예약 행을 잠그고 있어 공유 잠금이 더 막지 않는다.
--
-- 새 표만 더하므로 서비스 배포 전에 적용한다.
CREATE TABLE shop.preorder_payment_anomalies (
    preorder_id binary(16)  NOT NULL,
    paid_at     datetime(6) NOT NULL,
    order_id    varchar(64) NULL,
    detected_at datetime(6) NOT NULL,
    PRIMARY KEY (preorder_id, paid_at),
    CONSTRAINT fk_payment_anomaly_preorder FOREIGN KEY (preorder_id) REFERENCES shop.preorders (id)
) ENGINE = InnoDB;
