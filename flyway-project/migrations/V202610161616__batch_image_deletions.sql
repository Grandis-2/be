-- 사진 정리의 삭제 대기 ------------------------------------------
-- batch 의 사진 정리는 images 행을 id 로 먼저 지우고 커밋한 뒤 S3 객체를 지운다. 커밋 뒤 S3 삭제 전에 batch 가 멈추거나 S3 삭제가 실패하면
-- 행 없는 객체가 버킷에 남고, images 를 읽는 정리로는 다시 찾지 못한다.
--
-- 왜: 행 삭제와 같은 트랜잭션에 "이 키를 S3 에서 지울 것" 을 넣는다. S3 삭제가 성공하면 이 행을 지우고, 남은 행은 다음 실행이 처음에 마저 지운다.
--     S3 를 커밋 전에 지우지 않는 것은, 지운 뒤 커밋 전에 멈추면 행이 되살아나(UNUSED) 파일 없는 사진을 붙일 수 있어서다.
-- 쓰는 쪽: batch 만 넣고 지운다. catalog 는 읽지 않는다.
-- images 로 가는 외래 키는 두지 않는다 — 이 행을 넣을 때 images 행은 이미 지웠다.
-- 배포: 새 표만 더하므로 서비스 배포 전에 적용해도 된다.
CREATE TABLE shop.image_deletions (
    object_key  varchar(200) COLLATE utf8mb4_bin NOT NULL,   -- 지울 S3 객체 키. images.object_key 와 같은 값 · 콜레이션
    created_at  datetime(6)  NOT NULL,                       -- 행을 지운 시각(UTC). 오래 남은 행은 S3 삭제가 계속 실패하는 것이다
    PRIMARY KEY (object_key)
) ENGINE = InnoDB;
