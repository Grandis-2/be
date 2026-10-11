-- 상품의 옵션 축 · 값 · 사진을 JSON 한 칸(products.options)으로 옮기고, 썸네일 · 등록 멱등 키를 상품 칸으로 둔다(2026-10-07 결정).
--
-- options: {"axes":[{"key","label","values":[{"id","value","normalized","hex","surcharge","images":[{"url","primary"}]}]}],
--           "defaultImages":[…], "detailImages":[{"section","images":[…]}]}. 배열 순서가 곧 표시 순서다.
--   값 id 는 고정 문자열이다 — 새 값은 앱이 만든 32자 16진수, 여기서 옮기는 값은 옛 숫자 id 의 문자열. 그래서 옵션의 조합 키
--   (product_options.combination_key, 옛 값 id 를 숫자 순으로 '-' 로 이은 것)는 다시 쓰지 않는다 — 앱이 값 id 를 "짧은 것 먼저, 같으면
--   문자열 순" 으로 이어 옛 키와 같은 결과를 낸다.
-- thumbnail_url: 첫 색상(값 순서)의 첫 장, 색상 축이 없으면 기본 묶음의 첫 장. 첫 색상에 사진이 없으면 NULL, 대표 표시는 보지 않는다.
--   앱이 options 를 쓸 때 같이 쓴다(ProductOptions.thumbnailUrl 과 같은 규칙).
-- idempotency_key: 등록 기록(product_registrations)의 멱등 키. 등록 API 밖에서 들어온 행(다른 모듈 시험 픽스처)은 NULL — UNIQUE 는 NULL 끼리 겹쳐도 된다.
--
-- 다른 모듈 시험 픽스처가 products 를 기존 칸만으로 INSERT 하므로 options 는 DEFAULT {} 를 둔다.
-- 추가 · 채우기만 하므로 구버전 catalog 도 그대로 돈다 — 새 catalog 보다 먼저 적용한다(옛 표는 다음 마이그레이션이 지운다).
-- 이 마이그레이션부터 구버전 종료까지 상품 등록 · 옵션 수정을 받지 않는다. 구버전이 그 사이에 쓴 것은 옛 표에만 남고 여기서 옮긴 칸에 없다 —
-- 같은 멱등 키 재전송이 상품을 하나 더 만들고, 더한 값 · 조합이 새 버전 문서에서 빠진다. 다음 마이그레이션이 지우기 전에 어긋남을 보고 멈춘다.
--
-- 화면 규칙 변경(2026-10-07, 프론트 카드가 첫 옵션 색상의 사진을 보여 주는 것에 맞춘다): 썸네일은 옛 "기본 묶음의 대표, 없으면 색상 이름 사전순 첫 묶음의 대표, 대표가 없으면 없음"
-- 에서 "첫 색상(관리자가 넣은 순서)의 첫 장, 색상 축이 없으면 기본 묶음의 첫 장" 으로 바뀐다. 상세의 색상 묶음 순서도
-- 이름 사전순에서 넣은 순서로 바뀐다. 그래서 기존 상품의 썸네일이 달라질 수 있다.
-- 정렬된 배열을 만들려고 GROUP_CONCAT(ORDER BY) 로 JSON 글을 이어 붙인다(JSON_ARRAYAGG 는 순서를 받지 않는다). 기본 길이(1024)를 넘지 않게 늘린다.
SET SESSION group_concat_max_len = 16777216;

ALTER TABLE shop.products
    ADD COLUMN idempotency_key varchar(100) COLLATE utf8mb4_bin NULL AFTER id,
    ADD COLUMN options         json          NOT NULL DEFAULT (JSON_OBJECT()) AFTER base_price,
    ADD COLUMN thumbnail_url   varchar(1000) NULL AFTER options,
    ADD UNIQUE KEY uq_product_idempotency (idempotency_key);

UPDATE shop.products p
  JOIN shop.product_registrations r ON r.product_id = p.id
   SET p.idempotency_key = r.idempotency_key;

UPDATE shop.products p
   SET p.options = CAST(CONCAT(
        '{"axes":[',
        COALESCE((
            SELECT GROUP_CONCAT(JSON_OBJECT(
                       'key', a.axis_key,
                       'label', a.label,
                       'values', CAST(CONCAT('[', COALESCE((
                           SELECT GROUP_CONCAT(JSON_OBJECT(
                                      'id', CAST(v.id AS CHAR),
                                      'value', v.value,
                                      'normalized', v.normalized_value,
                                      'hex', NULL,
                                      'surcharge', v.surcharge,
                                      'images', CAST(CONCAT('[', COALESCE((
                                          SELECT GROUP_CONCAT(JSON_OBJECT(
                                                     'url', i.url,
                                                     'primary', IF(i.is_primary = 1, CAST('true' AS JSON), CAST('false' AS JSON)))
                                                 ORDER BY i.position SEPARATOR ',')
                                            FROM shop.product_images i
                                           WHERE i.product_id = p.id AND i.kind = 'GALLERY'
                                             AND a.axis_key = 'color' AND i.bundle_key = v.normalized_value), ''), ']') AS JSON))
                                  ORDER BY v.position SEPARATOR ',')
                             FROM shop.product_option_values v
                            WHERE v.axis_id = a.id), ''), ']') AS JSON))
                   ORDER BY a.position SEPARATOR ',')
              FROM shop.product_option_axes a
             WHERE a.product_id = p.id), ''),
        '],"defaultImages":[',
        COALESCE((
            SELECT GROUP_CONCAT(JSON_OBJECT(
                       'url', i.url,
                       'primary', IF(i.is_primary = 1, CAST('true' AS JSON), CAST('false' AS JSON)))
                   ORDER BY i.position SEPARATOR ',')
              FROM shop.product_images i
             WHERE i.product_id = p.id AND i.kind = 'GALLERY' AND i.bundle_key = ''), ''),
        '],"detailImages":[',
        COALESCE((
            SELECT GROUP_CONCAT(section_json ORDER BY section SEPARATOR ',')
              FROM (SELECT d.product_id, d.bundle_key AS section,
                           JSON_OBJECT('section', d.bundle_key, 'images', CAST(CONCAT('[', GROUP_CONCAT(JSON_OBJECT(
                               'url', d.url,
                               'primary', IF(d.is_primary = 1, CAST('true' AS JSON), CAST('false' AS JSON)))
                               ORDER BY d.position SEPARATOR ','), ']') AS JSON)) AS section_json
                      FROM shop.product_images d
                     WHERE d.kind = 'DETAIL'
                     GROUP BY d.product_id, d.bundle_key) s
             WHERE s.product_id = p.id), ''),
        ']}') AS JSON),
       p.thumbnail_url = COALESCE(
        (SELECT i.url
           FROM shop.product_option_axes a
           JOIN shop.product_option_values v ON v.axis_id = a.id
           JOIN shop.product_images i ON i.product_id = p.id AND i.kind = 'GALLERY' AND i.bundle_key = v.normalized_value
          WHERE a.product_id = p.id AND a.axis_key = 'color'
            AND v.position = (SELECT MIN(f.position) FROM shop.product_option_values f WHERE f.axis_id = a.id)
          ORDER BY i.position
          LIMIT 1),
        (SELECT i.url
           FROM shop.product_images i
          WHERE i.product_id = p.id AND i.kind = 'GALLERY' AND i.bundle_key = ''
            AND NOT EXISTS (SELECT 1 FROM shop.product_option_axes c WHERE c.product_id = p.id AND c.axis_key = 'color')
          ORDER BY i.position
          LIMIT 1));
