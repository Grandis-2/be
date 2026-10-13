-- 상품의 브랜드 · 모델명과 카테고리별 기본 옵션 축.
--
-- products.brand: 제조사 — 회원 목록의 브랜드 필터 · 상품 카드. 고정 목록(앱 enum Brand 와 같다). 늘릴 때는 CHECK 를 같이 바꾼다.
-- products.model_name: 화면에 상품명과 함께 보이는 모델명(예: A5390). 표시용이라 옵션 SKU 와 섞지 않는다.
-- categories.option_axes: 관리자 등록 화면이 상품 등록 요청의 optionAxes 를 처음에 채우는 틀 — [{"key": "color", "label": "색상"}, …].
--   기본값일 뿐 등록 검증은 이 칸을 보지 않는다(같은 카테고리라도 축이 다른 상품이 있다 — Wi-Fi 전용 태블릿에는 연결 축이 없다).
--   상위 카테고리와 축이 없는 카테고리는 []. 행 · 값은 마이그레이션이 아니라 운영 init.sql 이 넣는다.
--   다른 모듈 시험 픽스처가 categories 를 이 칸 없이 INSERT 하므로 DEFAULT 를 둔다.
--
-- 상품 두 칸은 기본값 없는 NOT NULL 이다 — 빠뜨린 INSERT 는 1364 로 거절된다(다른 모듈 시험 픽스처도 두 칸을 넣는다).
-- 상품 행이 이미 있는 DB 에서는 실패한다: 칸 추가가 기존 행을 '' 로 채우고 그 행이 ck_product_brand 에 걸린다(3819).
-- 기존 상품의 브랜드를 정할 규칙이 없어 채우지 않는다. 운영은 상품 0행에서 적용한다(2026-10-13).
-- products 를 먼저 바꾼다 — DDL 은 문장마다 커밋되어, 뒤 문장이 실패하면 앞 문장만 남은 채 이력이 실패로 남는다.
-- 이 순서면 행이 있어 실패할 때 아무 칸도 생기지 않는다(MySQL 8.4.11 · Flyway CLI 실측: 칸 0개, 지우고 다시 돌릴 것이 없다).
-- 구버전 catalog 는 두 칸 없이 INSERT 하므로 이 마이그레이션부터 구버전 종료까지 상품 등록을 받지 않는다(조회 · 수정은 그대로 돈다).
ALTER TABLE shop.products
    ADD COLUMN brand      varchar(20) NOT NULL AFTER title,
    ADD COLUMN model_name varchar(40) NOT NULL AFTER brand,
    ADD CONSTRAINT ck_product_brand CHECK (brand IN ('SAMSUNG', 'APPLE', 'LG'));

ALTER TABLE shop.categories
    ADD COLUMN option_axes json NOT NULL DEFAULT (JSON_ARRAY()) AFTER sort_order,
    ADD CONSTRAINT ck_category_option_axes CHECK (JSON_TYPE(option_axes) = 'ARRAY');
