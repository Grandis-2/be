-- catalog_promotions
--
-- 사전예약 홍보(catalog 소유). 관리자가 홍보 하나에 사전예약 상품 여러 개를 묶어 고객 사전예약 페이지에 보인다.
-- 홍보 기간(start_date ~ end_date, 한국 날짜, 끝 날 포함)은 고객 페이지에 보이는 기간이다. 접수 오픈 · 마감은 상품마다 preorder 회차가 정한다.
-- 한 상품은 홍보 하나에만 묶인다(uq_promotion_product). 묶을 때는 사전예약 상품만 받고, 마감 뒤 일반 상품으로 바꿔도 묶음은 남는다(앱 규칙).
-- 사진은 상품과 같은 업로드 주소만 받는다(앱 규칙). 상세 사진은 넣은 순서의 주소 배열이다.
--
-- 새 표만 더하므로 새 버전보다 먼저 적용해도 된다.
CREATE TABLE shop.promotions (
    id                binary(16)    NOT NULL,
    -- 관리자 만들기의 Idempotency-Key. 같은 키로 다시 오면 이 홍보를 돌려준다 — 응답을 잃고 다시 보내도 홍보가 둘이 되지 않는다
    idempotency_key   varchar(100)  COLLATE utf8mb4_bin NOT NULL,
    name              varchar(100)  NOT NULL,
    start_date        date          NOT NULL,
    end_date          date          NOT NULL,
    thumbnail_url     varchar(1000) NOT NULL,
    detail_image_urls json          NOT NULL,
    created_at        datetime(6)   NOT NULL,
    updated_at        datetime(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_promotion_idempotency (idempotency_key),
    KEY ix_promotion_period (start_date, end_date),
    KEY ix_promotion_created (created_at, id),
    CONSTRAINT ck_promotion_period CHECK (start_date <= end_date),
    CONSTRAINT ck_promotion_detail_images CHECK (JSON_TYPE(detail_image_urls) = 'ARRAY')
) ENGINE = InnoDB;

-- 홍보에 묶인 상품. position 은 고객 페이지에 보이는 순서(0 부터)다.
CREATE TABLE shop.promotion_products (
    promotion_id binary(16) NOT NULL,
    position     int        NOT NULL,
    product_id   binary(16) NOT NULL,
    PRIMARY KEY (promotion_id, position),
    UNIQUE KEY uq_promotion_product (product_id),
    CONSTRAINT fk_promotion_product_promotion FOREIGN KEY (promotion_id) REFERENCES shop.promotions (id),
    CONSTRAINT fk_promotion_product_product FOREIGN KEY (product_id) REFERENCES shop.products (id),
    CONSTRAINT ck_promotion_product_position CHECK (position >= 0)
) ENGINE = InnoDB;
