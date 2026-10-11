-- 공개 주문 번호(order_token)를 주문 id(orders.id)로 합친다. 이 마이그레이션은 칸을 DB 가 채우게만 한다.
--
-- 왜: order_token 은 "내부 id(UUID v7)는 만든 시각이 드러난다" 는 이유로 따로 둔 공개 번호다. 그런데 주문 응답이 만든 시각(createdAt)을
--     이미 싣고 목록 커서도 id 를 담는다 — 숨기려던 값이 이미 밖에 나간다. 같은 주문을 두 값으로 가리킬 이유가 없다.
-- 새 버전은 이 칸을 쓰지 않는다(주문 API 의 orderId = orders.id). 같이 도는 구버전은 이 칸을 NOT NULL 로 읽고 이 값으로 주문을 찾으므로
-- 새 버전이 넣는 행은 DB 가 id 의 문자열로 채운다 — 구버전도 새 주문을 같은 번호로 읽고 찾는다. 구버전이 넣는 값은 그대로 둔다.
-- 배포: 기본값만 바꿔 메타데이터만 바뀐다(INSTANT — 아니면 서버가 거부한다). orders 를 외래 키로 가리키는 표에 열린 트랜잭션이
--     있으면 시작 전에 기다린다. 새 버전보다 먼저 적용한다.
-- 칸과 uq_order_token 은 구버전을 끈 뒤 지운다.

ALTER TABLE shop.orders
    ALTER COLUMN order_token SET DEFAULT (BIN_TO_UUID(id)),
    ALGORITHM = INSTANT;
