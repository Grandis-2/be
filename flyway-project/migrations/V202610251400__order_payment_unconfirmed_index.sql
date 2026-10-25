-- 결제 확인 실패로 취소한 주문을 최근 시간 범위로 세는 색인(order 소유).
--
-- 왜: 관리자 주문 수(GET /api/v1/admin/orders/stats)가 최근 1시간 · 24시간의 결제 확인 실패 건수를 준다(결제 장애 신호).
--     결제 확인 실패 취소는 unconfirmed_provider_order_id(V202610241600, ck_order_unconfirmed_attempt)가 있는 주문이고, 그 시각은
--     같은 취소 UPDATE 에서 적고 다시 바꾸지 않는 재고 반환 표식 stock_released_at(V202609221721)이다. 이력의 사유 문자열로 세지 않는다(V202610111303).
-- - orders 에 ix_order_released_unconfirmed (stock_released_at, unconfirmed_provider_order_id): 최근 24시간에 재고를 돌려준 주문만 색인 범위로
--   읽고, 결제 확인 실패인지는 색인 안에서 거른다(행을 읽지 않는다). 읽는 양은 그 24시간의 취소 · 만료 주문 수라 지난 장애가 쌓여도 늘지 않는다.
-- 대가는 재고를 돌려주는 취소 UPDATE 마다(그리고 주문 INSERT 마다 — 빈 값도 색인에 든다) orders 보조 색인 하나를 더 갱신하는 것이다.
--
-- 배포: INPLACE · LOCK=NONE 이라 그동안 쓰기를 막지 않는다(시작 · 끝의 짧은 메타데이터 잠금은 남는다 — 열린 트랜잭션이 있으면 기다린다).
--     지원하지 않으면 복사로 넘어가지 않고 실패한다. 구버전과 같이 돌아도 되므로 서비스 배포 전에 적용한다.
-- 중간 실패: 문장이 하나라 색인은 생기거나 없다. 실패하면 flyway.sh repair 로 실패 기록을 지우고 다시 적용한다.

ALTER TABLE shop.orders
    ADD KEY ix_order_released_unconfirmed (stock_released_at, unconfirmed_provider_order_id),
    ALGORITHM = INPLACE, LOCK = NONE;
