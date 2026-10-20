-- catalog_banners
--
-- 메인 배너(관리자가 만든다). 한 장의 칸은 화면의 배너와 같다 — 배지 · 제목 · 부가 설명 · 탭 문구 · 탭 아이콘(lucide 이름) · 사진(배경 없는 이미지) ·
-- 사진 대체 문구. 줄바꿈(\n)은 그대로 담는다.
-- 연결 대상(target_type · target_id)은 일반 판매 상품(PRODUCT) 또는 홍보(PROMOTION) 하나다. 외래 키는 두지 않는다 — 대상이 숨김 · 판매 중지 ·
-- 기간 끝이 되면 지우지 않고 고객 조회에서만 빠진다(자동 숨김). position 은 고객에게 보이는 순서(작은 것부터), visible 은 관리자가 끈 배너.
--
-- 새 표만 더하므로 새 버전보다 먼저 적용해도 된다.
CREATE TABLE shop.banners (
    id          binary(16)    NOT NULL,
    badge       varchar(20)   NOT NULL,
    title       varchar(100)  NOT NULL,
    description varchar(200)  NOT NULL,
    tab_label   varchar(60)   NOT NULL,
    icon        varchar(40)   NOT NULL,
    image_url   varchar(1000) NOT NULL,
    image_alt   varchar(100)  NOT NULL,
    target_type varchar(16)   NOT NULL,
    target_id   binary(16)    NOT NULL,
    position    int           NOT NULL,
    visible     tinyint(1)    NOT NULL,
    created_at  datetime(6)   NOT NULL,
    updated_at  datetime(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY ix_banner_position (position, id),
    CONSTRAINT ck_banner_target_type CHECK (target_type IN ('PRODUCT', 'PROMOTION')),
    CONSTRAINT ck_banner_position CHECK (position >= 0)
) ENGINE = InnoDB;
