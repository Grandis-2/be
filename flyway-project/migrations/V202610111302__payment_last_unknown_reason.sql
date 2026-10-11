-- 결제 거래의 마지막 결과가 승인 · 취소 명령의 결과 불명이면 그 사유를 따로 적는다.
--
-- 왜: 다음 복구가 같은 키로 다시 보낼지 · 조회할지를 문구 칸(last_error_message)이 불명 사유 이름과 같은지로 정해 왔다.
--     사람이 읽는 토스 문구와 기계 신호가 한 칸에 섞여, 문구 형식만 바꿔도 복구 동작이 바뀐다. 판정을 이 칸으로 옮기려고 둔다.
-- 값: 결제 서비스의 불명 사유 이름(TIMEOUT · CONNECTION_FAILURE · UNLISTED_CODE …). 명령의 결과 불명이 아니면
--     (일시 오류 · 거절 · 조회 결과) NULL 이다.
-- 구버전과 같이 도는 동안: 구버전은 문구 칸으로 판정하고, 오류를 지우거나 바꿀 때 이 칸을 모른다. 그래서
--     새 버전도 판정은 문구 칸으로 하고, 결과 불명이면 사유 이름을 두 칸에 같이 적는다. 판정을 옮기는 일과
--     "사유가 있으면 코드도 있다" CHECK 는 구버전을 끈 뒤 이 칸을 문구 칸에서 다시 채우고 한다 — 지금 CHECK 를 걸면
--     구버전이 성공을 확정하며 코드만 지울 때 깨진다.
-- 지금 행은 문구 칸의 사유 이름으로 채운다(대소문자 · 끝 공백까지 같을 때만).
-- 배포: 칸 추가는 메타데이터만 바뀐다(INSTANT — 아니면 서버가 거부한다). 채우기는 표 전체를 훑는다 — 지금 크기면 짧다.

ALTER TABLE shop.payment_transactions
    ADD COLUMN last_unknown_reason varchar(30) NULL AFTER last_error_message,
    ALGORITHM = INSTANT;

UPDATE shop.payment_transactions
   SET last_unknown_reason = last_error_message
 WHERE last_error_message COLLATE utf8mb4_0900_bin IN ('TIMEOUT', 'CONNECTION_FAILURE', 'UNREADABLE_RESPONSE', 'LISTED_CODE',
                                                       'UNLISTED_CODE', 'UNEXPECTED_STATUS', 'MISMATCHED_RESPONSE');
