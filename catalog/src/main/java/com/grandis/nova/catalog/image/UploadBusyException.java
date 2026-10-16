package com.grandis.nova.catalog.image;

/** 동시 처리 허가를 못 받았다. 503 에 Retry-After 를 실어야 해서 BusinessException 이 아니라 따로 둔다(컨트롤러가 응답을 만든다). */
public class UploadBusyException extends RuntimeException {

    private final int retryAfterSeconds;

    public UploadBusyException(int retryAfterSeconds) {
        super("image upload permits exhausted");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
