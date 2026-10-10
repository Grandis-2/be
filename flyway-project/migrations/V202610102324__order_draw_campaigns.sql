-- order_draw_campaigns
--
-- 럭키 드로우 회차(order 소유). 판매자가 정한 응모비를 결제한 응모자 중 추첨해 당첨자에게 증정한다.
-- 증정품은 일반 판매 상품의 옵션 하나다 — 매장에 안 파는 물건은 catalog 에 비공개 상품으로 먼저 등록한다. 회차를 만들 때 당첨 인원만큼
-- 그 옵션의 재고를 확보한다(option_inventories.stock_reserved). 표시는 만든 때의 catalog 값 스냅샷이다.
-- 상태(예정 · 응모 중 · 마감)는 opens_at · closes_at 으로 가른다. 응모 · 추첨 결과는 뒤 마이그레이션이 더한다.
--
-- 새 표만 더하므로 새 버전보다 먼저 적용해도 된다.
CREATE TABLE shop.draw_campaigns (
    id                     binary(16)    NOT NULL,
    product_id             binary(16)    NOT NULL,
    option_id              binary(16)    NOT NULL,
    title                  varchar(100)  NOT NULL,
    product_title_snapshot varchar(100)  NOT NULL,
    option_title_snapshot  varchar(120)  NOT NULL,
    image_url_snapshot     varchar(1000) NULL,
    entry_fee              decimal(12,0) NOT NULL,
    winner_count           int           NOT NULL,
    opens_at               datetime(6)   NOT NULL,
    closes_at              datetime(6)   NOT NULL,
    created_at             datetime(6)   NOT NULL,
    updated_at             datetime(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY ix_draw_campaign_created (created_at, id),
    CONSTRAINT fk_draw_campaign_option FOREIGN KEY (product_id, option_id) REFERENCES shop.product_options (product_id, id),
    CONSTRAINT ck_draw_campaign_entry_fee CHECK (entry_fee > 0),
    CONSTRAINT ck_draw_campaign_winner_count CHECK (winner_count >= 1),
    CONSTRAINT ck_draw_campaign_period CHECK (opens_at < closes_at)
) ENGINE = InnoDB;
