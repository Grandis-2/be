-- 카탈로그: 카테고리 2단계 · 상품 공개 여부 / 기본가 / 보증 · 옵션 축 / 값 / 선택 · 사진 · 등록 기록
--
-- 다른 모듈의 시험 픽스처(origin/epic/NV-29 preorder ShopFixtures · origin/epic/NV-45 order OrderFixtures)가
-- products · product_options 를 기존 칼럼만으로 INSERT 하므로 기존 표에 더하는 칼럼은 전부 DEFAULT 를 둔다. 새 표는 catalog 만 쓴다.
-- 노출 규칙: 등록 완료(product_registrations.completed_at) AND visible AND status = 'ACTIVE'
--            AND (sale_mode = 'IN_STOCK' OR preorder_campaigns.closes_at + 120h > now).
-- 전제: 이 마이그레이션 시점에 products 행이 없다(dev · 운영에 상품 데이터 없음, 2026-09-27 확인). 기존 행이 있으면 등록 기록이 없어
--       노출 규칙(completed_at 필수)에 걸려 오류 없이 전부 숨겨지므로 백필 마이그레이션이 필요하다.

-- 카테고리 --------------------------------------------------
-- 2단계. 상위(모바일 · PC · 액세서리) 아래 삼성 · Apple. 상품은 상위 또는 하위 하나에 배정한다.
-- 상위 목록은 상위 직접 배정 + 하위 배정 상품을 함께 보인다. 순환(parent_id = id 포함) · 3단계 금지는 앱 검사다 —
-- 자기참조 FK 로는 깊이를 제한할 수 없고, MySQL 은 AUTO_INCREMENT 칼럼을 CHECK 에 못 쓴다. 관리자 카테고리 CRUD 는 없으므로 행은 마이그레이션이 넣는데,
-- 이름이 프론트와 아직 안 맞아 이 파일은 구조만 바꾸고 행은 확정 뒤 별도 마이그레이션으로 넣는다.
-- option_filter_definitions 는 아래 옵션 축 · 값 표로 대체되어 폐기 예정이다.
-- 참조가 없음을 확인한 뒤 후속 마이그레이션에서 지운다(기존 파일은 고치지 않는다).
ALTER TABLE shop.categories
    ADD COLUMN parent_id bigint NULL AFTER id,
    ADD KEY ix_category_parent (parent_id),
    ADD CONSTRAINT fk_category_parent FOREIGN KEY (parent_id) REFERENCES shop.categories (id);

-- 상품 ------------------------------------------------------
-- visible 은 status 와 별개다. PAUSED 는 일반이면 판매 중지, 사전예약 오픈 후면 회차 취소.
-- 비공개는 숨김일 뿐 기존 예약 · 주문에 손대지 않는다.
-- base_price 는 옵션 가격 계산의 기준(기본가 + 값별 추가금). 옵션의 price 가 최종가라는 뜻은 그대로다.
-- 보증(AppleCare+ 류)은 상품 단위 설정이고 선택 · 단가 스냅샷은 예약 · 주문 표가 갖는다.
-- image_url 은 product_images 의 GALLERY 대표로 대체되어 폐기 예정이다. 읽는 모듈이 없음을 확인했고(preorder · order 는
-- 이 칸을 복사하지 않는다) catalog 도 매핑하지 않는다. 제거는 후속 마이그레이션.
ALTER TABLE shop.products
    ADD COLUMN base_price         decimal(12,0) NOT NULL DEFAULT 0 AFTER title,
    ADD COLUMN visible            tinyint(1)    NOT NULL DEFAULT 1 AFTER status,
    ADD COLUMN warranty_offered   tinyint(1)    NOT NULL DEFAULT 0 AFTER visible,
    ADD COLUMN warranty_surcharge decimal(12,0) NOT NULL DEFAULT 0 AFTER warranty_offered,
    ADD CONSTRAINT ck_product_base_price         CHECK (base_price >= 0),
    ADD CONSTRAINT ck_product_warranty_surcharge CHECK (warranty_surcharge >= 0);

-- 옵션 ------------------------------------------------------
-- price_overridden = 1 이면 관리자가 직접 고친 가격이라 기본가 · 추가금 재계산에서 건너뛴다.
-- combination_key 는 이 옵션이 고른 값 id 를 오름차순으로 '-' 로 이은 문자열("12-57"). **키를 채운 옵션끼리** 같은 조합을 UNIQUE 로
-- 막는다 — 선택은 집합이라 선택 표만으로는 UNIQUE 한 줄로 못 막는다. 축이 없는 상품의 옵션은 catalog 가 '' 로 넣어
-- 같은 UNIQUE 가 상품당 하나를 지킨다. NULL 은 catalog 밖(다른 모듈 픽스처)에서 넣은 행뿐이고 NULL 끼리는 중복 허용이라
-- 키 · filter_attributes · 선택 행이 서로 맞는지는 DB 가 아니라 한 팩토리(OptionCombination)가 셋을 함께 만들어 보장한다.
ALTER TABLE shop.product_options
    ADD COLUMN price_overridden tinyint(1)   NOT NULL DEFAULT 0 AFTER price,
    ADD COLUMN combination_key  varchar(200) NULL AFTER display_attributes,
    ADD UNIQUE KEY uq_option_combination (product_id, combination_key);

-- 옵션 축 ---------------------------------------------------
-- 상품이 받는 옵션의 종류. axis_key 는 color · storage(목록 필터 키)거나 관리자가 입력한 키(예: length).
-- 색상은 별도 관리라는 결정은 "축 하나가 color" 라는 뜻이고 표를 따로 두지 않는다.
-- axis_key 는 utf8mb4_bin(대소문자 구분)이라 앱이 소문자로 접어 저장한다 — 안 접으면 Color 와 color 가 공존하고 필터 판정이 빗나간다.
CREATE TABLE shop.product_option_axes (
    id         bigint      NOT NULL AUTO_INCREMENT,
    product_id bigint      NOT NULL,
    axis_key   varchar(40) COLLATE utf8mb4_bin NOT NULL,
    label      varchar(60) NOT NULL,
    position   int         NOT NULL,
    created_at datetime(6) NOT NULL,
    updated_at datetime(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_option_axis_key     (product_id, axis_key),
    -- 선택 표의 상품-축 복합 FK 대상
    UNIQUE KEY uq_option_axis_product (product_id, id),
    CONSTRAINT fk_option_axis_product FOREIGN KEY (product_id) REFERENCES shop.products (id),
    CONSTRAINT ck_option_axis_position CHECK (position >= 0)
) ENGINE = InnoDB;

-- 옵션 값 ---------------------------------------------------
-- value 는 표시값, normalized_value 는 비교 · 필터 키(색상: NFC · 트림 · 공백 하나, 용량: 숫자+대문자 단위 "256GB").
-- 기본 콜레이션(utf8mb4_0900_ai_ci)이 UNIQUE 에서 같게 보는 것과 아닌 것(MySQL 8.4 실측):
--   같다  '256GB'='256gb' · 'Rose'='Rosé'(악센트) · '256GB'='２５６ＧＢ'(전각) · '블랙'(NFC)='블랙'(NFD)
--   다르다 '블랙'≠'블랙 '(NO PAD — 트림은 앱) · 'Space Gray'≠'Space  Gray'(공백 접기는 앱)
-- 그래서 앱 정규화에서 필수인 것은 트림 · 공백 하나로 접기다. product_images.bundle_key 도 같은 콜레이션이라 같은 규칙이다.
CREATE TABLE shop.product_option_values (
    id               bigint        NOT NULL AUTO_INCREMENT,
    axis_id          bigint        NOT NULL,
    value            varchar(60)   NOT NULL,
    normalized_value varchar(60)   NOT NULL,
    surcharge        decimal(12,0) NOT NULL DEFAULT 0,
    position         int           NOT NULL,
    created_at       datetime(6)   NOT NULL,
    updated_at       datetime(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_option_value      (axis_id, normalized_value),
    -- 선택 표의 축-값 복합 FK 대상
    UNIQUE KEY uq_option_value_axis (axis_id, id),
    CONSTRAINT fk_option_value_axis FOREIGN KEY (axis_id) REFERENCES shop.product_option_axes (id),
    CONSTRAINT ck_option_value_surcharge CHECK (surcharge >= 0),
    CONSTRAINT ck_option_value_position  CHECK (position >= 0)
) ENGINE = InnoDB;

-- 옵션 선택 -------------------------------------------------
-- 조합(product_options 행)이 축마다 고른 값. 판매하지 않을 조합은 product_options 행 자체를 만들지 않는다.
-- 복합 FK 셋이 "옵션과 축이 같은 상품" · "값이 그 축의 값" 을 DB 에서 지키고, PK 가 "축마다 값 하나" 를 지킨다.
-- DB 가 못 지키는 것 둘은 앱 책임이다: 옵션이 모든 축에 값을 갖는가(완전성 — 축을 나중에 더하면 기존 옵션이 전부 불완전해진다),
-- 같은 조합의 옵션이 둘인가(키를 채운 옵션끼리는 product_options.combination_key UNIQUE 가 맡는다).
-- 재계산은 price_overridden = 0 인 옵션만 base_price + Σ surcharge 로 다시 쓴다.
-- product_options.filter_attributes(color · storage) · title · combination_key 는 이 표에서 만들어 **같은 트랜잭션에서** 채운다.
-- 어긋나면 이 표가 정본이다. preorder 가 접수 때 그 JSON 을 복사하므로 한 번 어긋나면 예약 스냅샷에 박힌다.
CREATE TABLE shop.product_option_selections (
    product_id bigint NOT NULL,
    option_id  bigint NOT NULL,
    axis_id    bigint NOT NULL,
    value_id   bigint NOT NULL,
    PRIMARY KEY (option_id, axis_id),
    -- 필터: 값 → 옵션
    KEY ix_option_selection_value (value_id, option_id),
    CONSTRAINT fk_option_selection_option FOREIGN KEY (product_id, option_id) REFERENCES shop.product_options (product_id, id),
    CONSTRAINT fk_option_selection_axis   FOREIGN KEY (product_id, axis_id)   REFERENCES shop.product_option_axes (product_id, id),
    CONSTRAINT fk_option_selection_value  FOREIGN KEY (axis_id, value_id)     REFERENCES shop.product_option_values (axis_id, id)
) ENGINE = InnoDB;

-- 사진 ------------------------------------------------------
-- GALLERY 는 색상별 묶음(bundle_key = 정규화한 색상값, 색상 없는 상품은 ''), DETAIL 은 상세 영역별 묶음(bundle_key = 영역 이름).
-- bundle_key 를 NULL 로 두면 UNIQUE 가 중복을 못 막으므로 NOT NULL 빈 문자열이다.
-- 묶음당 대표 ≤ 1 은 DB 가 지킨다: primary_marker 는 DB 가 계산한다(preorders.active_marker 와 같은 수법. NULL 끼리는 중복 허용).
-- "사진이 있으면 대표가 있다"(≥ 1)와 대표 삭제 뒤 첫 사진 자동 대표는 앱 책임이다.
-- GALLERY 묶음당 10장 상한은 개수 제약이라 앱이 COUNT 와 행 잠금으로 지킨다. DETAIL 에는 상한 결정이 없다.
-- MySQL 은 UNIQUE 를 문장 끝이 아니라 행마다 검사한다(8.4.11 실측). 그래서 두 행의 순서 **교환**은 한 UPDATE 로 안 된다(1062) —
-- 임시 오프셋 두 단계나 지우고 다시 넣기. 한 방향 **이동**(밀기 +1 · 당기기 -1)은 되지만 ORDER BY 가 없으면 성패가 행 순서에
-- 달리므로(밀기: id 정순 1062 · 역순 성공) 방향에 맞는 ORDER BY 를 항상 명시한다 — 밀기는 position DESC, 당기기는 ASC.
-- 대표 교체도 해제를 먼저 flush 한 뒤 지정한다.
-- 저장소(URL 의 출처)는 인프라 결정이다.
CREATE TABLE shop.product_images (
    id             bigint        NOT NULL AUTO_INCREMENT,
    product_id     bigint        NOT NULL,
    kind           varchar(20)   NOT NULL,
    bundle_key     varchar(60)   NOT NULL DEFAULT '',
    position       int           NOT NULL,
    url            varchar(1000) NOT NULL,
    is_primary     tinyint(1)    NOT NULL DEFAULT 0,
    primary_marker tinyint       GENERATED ALWAYS AS (CASE WHEN is_primary THEN 1 ELSE NULL END) STORED,
    created_at     datetime(6)   NOT NULL,
    updated_at     datetime(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_product_image_position (product_id, kind, bundle_key, position),
    UNIQUE KEY uq_product_image_primary  (product_id, kind, bundle_key, primary_marker),
    CONSTRAINT fk_product_image_product FOREIGN KEY (product_id) REFERENCES shop.products (id),
    CONSTRAINT ck_product_image_kind     CHECK (kind IN ('GALLERY','DETAIL')),
    CONSTRAINT ck_product_image_position CHECK (position >= 0)
) ENGINE = InnoDB;

-- 등록 기록 -------------------------------------------------
-- 관리자의 "한 번 등록" 은 catalog 저장 → preorder(회차 · 차수) 또는 order(재고) 호출 → 완료의 여러 단계다.
-- 상품당 한 행. 단계마다 완료 시각을 두고 FAILED 같은 상태값은 두지 않는다 —
-- 실패는 "완료 시각이 비어 있고 last_error 가 있다" 로 읽고, 같은 Idempotency-Key 로 재개한다.
-- request_hash 는 정규화한 요청 본문의 SHA-256. 같은 키에 다른 본문이 오면 409.
-- requested_visible 은 관리자가 고른 공개 여부. 완료 전에는 products.visible 에 쓰지 않는다.
-- blocked_reason 은 자동 재개가 불가능해진 이유(예: 회차 저장 전에 오픈 시각이 지남). 이 행이 있는 상품은 영원히 미완료다.
-- last_error 는 500자 — 예외 메시지는 쉽게 넘고, 넘으면 오류를 기록하는 UPDATE 가 1406 으로 실패해 오류가 사라진다.
-- 쓰는 쪽이 코드포인트 기준으로 자른다.
-- lease_token · lease_expires_at 은 동시 재개 제어(preorder_sync_jobs 와 같은 장치). 둘은 같이 있거나 같이 없다(CHECK).
CREATE TABLE shop.product_registrations (
    product_id        bigint       NOT NULL,
    idempotency_key   varchar(100) COLLATE utf8mb4_bin NOT NULL,
    request_hash      binary(32)   NOT NULL,
    requested_visible tinyint(1)   NOT NULL,
    campaign_set_at   datetime(6)  NULL,
    batches_set_at    datetime(6)  NULL,
    stock_set_at      datetime(6)  NULL,
    completed_at      datetime(6)  NULL,
    blocked_reason    varchar(100) NULL,
    last_error        varchar(500) NULL,
    lease_token       varchar(64)  NULL,
    lease_expires_at  datetime(6)  NULL,
    created_at        datetime(6)  NOT NULL,
    updated_at        datetime(6)  NOT NULL,
    PRIMARY KEY (product_id),
    UNIQUE KEY uq_registration_key (idempotency_key),
    CONSTRAINT fk_registration_product FOREIGN KEY (product_id) REFERENCES shop.products (id),
    CONSTRAINT ck_registration_lease   CHECK ((lease_token IS NULL) = (lease_expires_at IS NULL)),
    -- 막힌 등록은 완료될 수 없고 완료된 등록은 막힐 수 없다
    CONSTRAINT ck_registration_outcome CHECK (NOT (blocked_reason IS NOT NULL AND completed_at IS NOT NULL))
) ENGINE = InnoDB;
