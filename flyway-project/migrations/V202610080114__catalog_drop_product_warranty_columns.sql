-- products.warranty_offered · warranty_surcharge 를 지운다 — 옵션 문서의 warranty 가 대신한다(V202610080113).
--
-- 적용 순서: 새 catalog 배포 → 구버전 catalog 종료 → 이 마이그레이션. 구버전은 이 칸을 읽고 쓰므로 먼저 지우면 깨지고,
-- 새 버전은 이 칸을 모른다(남아 있어도 돈다 — 새로 넣는 행은 기본값 0 이 들어간다).
-- (호환되지 않는 정리는 구버전 종료 뒤 — flyway-project/README 배포 순서)
-- V202610080113 과 한 릴리스에 들어 있다 — 먼저 `flyway.sh migrate -target=202610080113` 로 옮기기까지만 적용하고, 구버전 종료 뒤 target 없이 적용한다.
--
-- 지우기 전에 warranty 키가 없는 행을 칸에서 다시 채운다. 키가 빠지는 길은 V202610080113 뒤에 구버전이 옵션 문서를 다시 쓴 것뿐이다
-- (새 버전은 늘 키를 쓴다). 그 행의 칸은 구버전이 계속 고쳐 온 값이라 그대로 옮기면 된다.
-- 키가 있는 행의 문서와 칸이 다른 것은 어긋남으로 보지 않는다 — 새 버전의 보증 수정은 칸을 바꾸지 않아 갈리는 것이 정상이다. 그래서 동결이 필요하다.
UPDATE shop.products
   SET options = JSON_SET(options, '$.warranty',
           JSON_OBJECT('offered', IF(warranty_offered = 1, CAST('true' AS JSON), CAST('false' AS JSON)),
                       'surcharge', IF(warranty_offered = 1, warranty_surcharge, 0)))
 WHERE JSON_CONTAINS_PATH(options, 'one', '$.warranty') = 0;

-- ck_product_warranty_surcharge 는 이 칸 하나만 보는 CHECK 라 칸과 함께 사라진다. 추가금 범위(0 이상 · 정수 원 · 상한)는 앱이 지킨다.
ALTER TABLE shop.products
    DROP COLUMN warranty_offered,
    DROP COLUMN warranty_surcharge;
