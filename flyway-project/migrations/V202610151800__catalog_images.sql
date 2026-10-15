-- catalog_images
--
-- 관리자가 올린 상품 · 홍보 사진(catalog 소유). 업로드 API 가 파일을 검사 · 재인코딩해 S3 에 올리기 전에 행을 넣고, 상품 · 홍보 저장이 붙이고 뗀다.
-- 상품 · 홍보 문서는 지금처럼 사진 주소를 그대로 들고 있고, 이 표는 그 주소를 지금 누가 쓰는지 · 지워도 되는지만 안다.
--
-- status: UNUSED — 지금 아무 데도 안 붙었다(방금 올림 · 올리고 등록하지 않음 · 쓰다가 빠지거나 바뀜). USED — 상품 · 홍보가 쓰고 있다.
--         한 사진은 한 곳에만 붙는다(USED 는 다른 곳이 붙일 수 없다 — 앱 규칙).
-- updated_at: 상태가 바뀐 시각(UTC, 앱 시계). 정리 배치가 이 시각이 기준 시간보다 오래된 UNUSED 만 지운다 — 방금 올린 사진은 건드리지 않는다.
-- 정리 배치(batch)는 행을 id 로 먼저 지우고(드로우 스냅샷이 그 키를 가리키지 않을 때만) 커밋한 뒤 S3 객체를 지운다. 붙이기 · 떼기도 id 로 잠근다 —
-- object_key 로 잠그면 서로 다른 인덱스 순서로 잠가 교착한다(MySQL 8.4.11 실측).
--
-- 새 표만 더하므로 새 버전보다 먼저 적용해도 된다.
CREATE TABLE shop.images (
    id          binary(16)   NOT NULL,
    -- S3 객체 키 — products/{id}.jpg · promotions/{id}.png. 이전 presigned 업로드로 올린 주소를 옮겨 넣은 행은 id 가 키 안의 UUID(v4)다
    object_key  varchar(200) COLLATE utf8mb4_bin NOT NULL,
    status      varchar(16)  NOT NULL,
    created_at  datetime(6)  NOT NULL,
    updated_at  datetime(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_image_object_key (object_key),
    KEY ix_image_cleanup (status, updated_at),
    CONSTRAINT ck_image_status CHECK (status IN ('UNUSED', 'USED'))
) ENGINE = InnoDB;
