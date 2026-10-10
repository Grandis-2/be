-- order_draw_entries
--
-- 럭키 드로우 응모(order 소유). 회차당 회원 하나다(uq_draw_entry_customer). 결제 금액은 회차의 응모비다(회차는 만든 뒤 바꾸지 않는다).
-- 배송지는 응모할 때 받는다(당첨되면 이 배송지로 증정한다). 칸 · 길이는 orders 의 배송지와 같다.
--
-- 상태: AWAITING_PAYMENT(결제 대기) → AUTHORIZING(승인 중) → PAID(결제 완료). 거절 · 결제창 만료면 결제 대기로 돌아가 다시 결제할 수 있다.
-- 환불은 없다(낙첨 응모비도 돌려주지 않는다) — PAID 는 끝 상태다. 추첨 대상은 PAID 뿐이다.
-- authorizing_provider_order_id 는 orders 의 같은 칸과 같은 규칙이다: 승인 중일 때만 있고(ck_draw_entry_authorizing), 거절 · 되돌림은
-- 이 번호가 같을 때만 반영한다(늦게 온 이전 결제창의 거절이 새 결제창의 승인 중을 되돌리지 않게). 길이 · 콜레이션은
-- payment_transactions.provider_order_id 와 같다.
--
-- 새 표만 더하므로 새 버전보다 먼저 적용해도 된다.
CREATE TABLE shop.draw_entries (
    id                            binary(16)   NOT NULL,
    campaign_id                   binary(16)   NOT NULL,
    customer_id                   binary(16)   NOT NULL,
    status                        varchar(20)  NOT NULL,
    authorizing_provider_order_id varchar(64)  COLLATE utf8mb4_bin NULL,
    ship_to_name                  varchar(50)  NOT NULL,
    ship_to_phone                 varchar(20)  NOT NULL,
    ship_to_postal_code           varchar(10)  NOT NULL,
    ship_to_line1                 varchar(200) NOT NULL,
    ship_to_line2                 varchar(200) NULL,
    created_at                    datetime(6)  NOT NULL,
    updated_at                    datetime(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_draw_entry_customer (campaign_id, customer_id),
    CONSTRAINT fk_draw_entry_campaign FOREIGN KEY (campaign_id) REFERENCES shop.draw_campaigns (id),
    CONSTRAINT fk_draw_entry_customer FOREIGN KEY (customer_id) REFERENCES shop.customers (id),
    CONSTRAINT ck_draw_entry_status CHECK (status IN ('AWAITING_PAYMENT','AUTHORIZING','PAID')),
    CONSTRAINT ck_draw_entry_authorizing CHECK ((status = 'AUTHORIZING') = (authorizing_provider_order_id IS NOT NULL))
) ENGINE = InnoDB;
