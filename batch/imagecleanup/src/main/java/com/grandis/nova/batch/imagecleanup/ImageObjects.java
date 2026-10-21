package com.grandis.nova.batch.imagecleanup;

/** 업로드 버킷의 사진 객체. dry-run 이면 S3 를 부르지 않는다({@link #DRY_RUN}) — 버킷 · 리전 설정 없이 뜬다. */
interface ImageObjects extends AutoCloseable {

    /** dry-run 의 자리. 정리 서비스는 dry-run 이면 지우기 전에 돌아가므로 불리면 코드 오류다 */
    ImageObjects DRY_RUN = new ImageObjects() {

        @Override
        public void delete(String objectKey) {
            throw new IllegalStateException("dry-run 은 S3 객체를 지우지 않는다: " + objectKey);
        }

        @Override
        public void close() {
        }
    };

    /** 객체를 지운다. 없는 객체를 지워도 성공이다. 실패하면(거절 · 시간 초과) SDK 예외를 그대로 던진다 */
    void delete(String objectKey);

    @Override
    void close();
}
