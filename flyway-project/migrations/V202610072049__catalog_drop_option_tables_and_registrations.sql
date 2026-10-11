-- 옵션 축 · 값 · 선택 · 사진 · 등록 기록 표를 지운다 — products.options(JSON) · thumbnail_url · idempotency_key 가 대신한다(V202610072048).
--
-- 적용 순서: 새 catalog 배포 → 구버전 catalog 종료 → 이 마이그레이션. 구버전은 이 표들을 읽고 쓰므로 먼저 지우면 깨지고,
-- 새 버전은 이 표들을 모른다(남아 있어도 돈다). (호환되지 않는 정리는 구버전 종료 뒤 — flyway-project/README 배포 순서)
-- V202610072048 적용부터 구버전 종료까지 등록 · 옵션 수정을 받지 않는다(그 마이그레이션 주석). 구버전이 그 사이에 쓴 것은 옛 표에만 남는다.
--
-- 지우기 전에 옛 표(정본)와 옮긴 칸이 맞는지 본다. 어긋나면 아래 DO 문이 오류 3141(Invalid JSON text)로 멈추고 아무 표도 지우지 않는다 —
-- Flyway 가 알려 주는 Line 이 그 DO 문이다(문장 원문은 -X 에서만 나온다). 그 상품을 옛 표에서 다시 옮긴 뒤(V202610072048 의 UPDATE 를
-- 그 상품에) flyway repair 로 실패 기록을 지우고 다시 적용한다.
--   1) 등록 기록의 멱등 키 ≠ products.idempotency_key — 구버전이 그 사이에 등록한 상품
--   2) 옛 값 id 가 options 에 없음 — 구버전이 그 사이에 더한 값(그 값을 고른 조합도 여기서 걸린다)
-- 구버전이 기존 값으로 더한 조합은 보지 않는다 — 조합 키가 옛 값 id 로 되어 있어 새 버전이 문서에서 그대로 읽는다.
-- 값 이름 수정도 보지 않는다 — 새 버전이 고친 이름과 옛 표의 이름이 갈리는 것이 정상이라 거짓 경보가 된다. 그래서 동결이 필요하다.
DO IF((SELECT COUNT(*) FROM shop.product_registrations r JOIN shop.products p ON p.id = r.product_id
        WHERE NOT (p.idempotency_key <=> r.idempotency_key))
    + (SELECT COUNT(*) FROM shop.product_option_values v
         JOIN shop.product_option_axes a ON a.id = v.axis_id
         JOIN shop.products p ON p.id = a.product_id
        WHERE JSON_SEARCH(p.options, 'one', CAST(v.id AS CHAR), NULL, '$.axes[*].values[*].id') IS NULL) = 0,
    NULL, JSON_EXTRACT('options backfill drift', '$'));

-- 지우는 순서는 FK 를 따른다: 선택 → 값 → 축, 사진 · 등록 기록은 products 만 가리킨다.
DROP TABLE shop.product_option_selections;
DROP TABLE shop.product_option_values;
DROP TABLE shop.product_option_axes;
DROP TABLE shop.product_images;
DROP TABLE shop.product_registrations;
