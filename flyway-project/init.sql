-- 운영 shop 의 초기 데이터. 마이그레이션이 아니라 직접 적용한다(README "초기 데이터(init.sql)").
-- 기본 카테고리 14행: 이름 · 순서는 fe 헤더 메뉴(brandMenus)와 같고, 하위 카테고리에는 관리자 등록 화면이 처음 채우는 기본 옵션 축을 둔다.
-- id 는 2026-10-13 운영에 넣은 값으로 고정한다. 그래서 몇 번을 돌려도 같은 14행이 된다 — 없으면 넣고, 있으면 이 파일 값으로 맞춘다.
-- 파일에서 뺀 카테고리는 지우지 않는다. 상품이 걸려 있을 수 있어서다(fk_product_category).
-- 클라이언트 기본 문자셋이 latin1 이면(mysql 컨테이너가 그렇다) 한글이 깨져 들어가므로 먼저 utf8mb4 로 맞춘다.
SET NAMES utf8mb4;
SET @now = UTC_TIMESTAMP(6);
INSERT INTO shop.categories (id, parent_id, name, sort_order, option_axes, created_at, updated_at) VALUES
    (UUID_TO_BIN('01a133a3-d94c-78e7-9bae-ed7f80d8d709'), NULL, '모바일', 0, '[]', @now, @now),
    (UUID_TO_BIN('01a133a3-d94d-7958-85a4-fa6c465f50d0'), UUID_TO_BIN('01a133a3-d94c-78e7-9bae-ed7f80d8d709'), '스마트폰', 0,
     '[{"key": "color", "label": "색상"}, {"key": "storage", "label": "저장 용량"}]', @now, @now),
    (UUID_TO_BIN('01a133a3-d94e-7e42-8bb0-69853c948281'), UUID_TO_BIN('01a133a3-d94c-78e7-9bae-ed7f80d8d709'), '태블릿', 1,
     '[{"key": "color", "label": "색상"}, {"key": "storage", "label": "저장 용량"}, {"key": "connectivity", "label": "연결"}]', @now, @now),
    (UUID_TO_BIN('01a133a3-d94f-741b-b53f-4110bef37ebb'), UUID_TO_BIN('01a133a3-d94c-78e7-9bae-ed7f80d8d709'), '폴더블', 2,
     '[{"key": "color", "label": "색상"}, {"key": "storage", "label": "저장 용량"}]', @now, @now),
    (UUID_TO_BIN('01a133a3-d950-7f8a-9565-d515d6ac72a8'), NULL, 'PC/주변기기', 1, '[]', @now, @now),
    (UUID_TO_BIN('01a133a3-d951-7e5f-8089-a1eefe74f9e3'), UUID_TO_BIN('01a133a3-d950-7f8a-9565-d515d6ac72a8'), '노트북', 0,
     '[{"key": "color", "label": "색상"}, {"key": "processor", "label": "프로세서"}, {"key": "memory", "label": "메모리"}, {"key": "storage", "label": "저장 용량"}]', @now, @now),
    (UUID_TO_BIN('01a133a3-d952-7c2c-bd71-f9d7a3f4186e'), UUID_TO_BIN('01a133a3-d950-7f8a-9565-d515d6ac72a8'), '데스크탑', 1,
     '[{"key": "processor", "label": "프로세서"}, {"key": "memory", "label": "메모리"}, {"key": "storage", "label": "저장 용량"}]', @now, @now),
    (UUID_TO_BIN('01a133a3-d953-7204-b21d-de94b31fe8f1'), UUID_TO_BIN('01a133a3-d950-7f8a-9565-d515d6ac72a8'), '모니터', 2, '[]', @now, @now),
    (UUID_TO_BIN('01a133a3-d954-7eac-8360-5eff1d375f22'), UUID_TO_BIN('01a133a3-d950-7f8a-9565-d515d6ac72a8'), '키보드', 3,
     '[{"key": "color", "label": "색상"}, {"key": "keyboard_language", "label": "키보드 언어"}]', @now, @now),
    (UUID_TO_BIN('01a133a3-d955-7950-b8f9-d14a3b1b54d9'), UUID_TO_BIN('01a133a3-d950-7f8a-9565-d515d6ac72a8'), '마우스', 4,
     '[{"key": "color", "label": "색상"}]', @now, @now),
    (UUID_TO_BIN('01a133a3-d956-7d9d-acf6-345e08117f60'), NULL, '웨어러블', 2, '[]', @now, @now),
    (UUID_TO_BIN('01a133a3-d957-7145-be8c-56dd59b58d8b'), UUID_TO_BIN('01a133a3-d956-7d9d-acf6-345e08117f60'), '스마트워치', 0,
     '[{"key": "case_size", "label": "케이스 크기"}, {"key": "color", "label": "색상"}, {"key": "connectivity", "label": "연결"}]', @now, @now),
    (UUID_TO_BIN('01a133a3-d958-783a-a6f8-0718815493f0'), UUID_TO_BIN('01a133a3-d956-7d9d-acf6-345e08117f60'), '무선이어폰', 1,
     '[{"key": "color", "label": "색상"}]', @now, @now),
    (UUID_TO_BIN('01a133a3-d959-7398-8cb8-5bdcd8c651c6'), UUID_TO_BIN('01a133a3-d956-7d9d-acf6-345e08117f60'), '스마트밴드', 2,
     '[{"key": "color", "label": "색상"}]', @now, @now)
AS new
ON DUPLICATE KEY UPDATE parent_id   = new.parent_id,
                        name        = new.name,
                        sort_order  = new.sort_order,
                        option_axes = new.option_axes,
                        updated_at  = new.updated_at;
