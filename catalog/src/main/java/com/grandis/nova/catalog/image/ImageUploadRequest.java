package com.grandis.nova.catalog.image;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 올릴 파일 하나. 형식 · 크기는 서명에 들어간다 — 브라우저가 다른 형식(Content-Type)이나 다른 크기의 파일을 그 URL 로 올리면 S3 가 403 으로 거절한다.
 *
 * @param contentType   파일 형식(image/jpeg · image/png · image/webp)
 * @param contentLength 파일 크기(바이트). 1 ~ 10MiB(10,485,760 바이트)
 */
public record ImageUploadRequest(@NotBlank String contentType, @NotNull Long contentLength) {
}
