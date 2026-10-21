-- preorder_campaigns.expected_visitors — 관리자가 넣는 회차의 예상 방문자 수(preorder 소유).
--
-- 왜: 인프라가 오픈 전에 미리 늘릴 규모를 정할 값이 회차에 없었다. 오픈 알림 신청 수는 실제 방문자보다 적어 그것만으로는 모자란다.
-- 값: 1 이상 정수(관리자 API 가 검사한다). NULL = 넣지 않았다. 오픈 전에만 바꾸고, 바꾸면 일정 번호가 오른다(앱).
--
-- CHECK 는 두지 않는다 — 접수가 줄 서는 행이라 표 복사(ALGORITHM=COPY) 없이 NULL 칸만 더한다(INSTANT).
-- 구버전과 같이 돌아도 된다 — 구버전은 이 칸을 모르고 읽지 않는다. 서비스 배포 전에 적용한다.
ALTER TABLE shop.preorder_campaigns
    ADD COLUMN expected_visitors int NULL,
    ALGORITHM = INSTANT;
