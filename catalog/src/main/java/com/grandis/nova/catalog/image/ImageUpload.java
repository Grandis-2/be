package com.grandis.nova.catalog.image;

import java.time.Instant;
import java.util.Map;

/**
 * 발급한 업로드 하나.
 *
 * @param uploadUrl 브라우저가 파일을 PUT 할 주소(서명 포함)
 * @param headers   PUT 에 그대로 실어야 하는 헤더(Content-Type). Content-Length 는 브라우저가 파일 크기로 채운다
 * @param imageUrl  올린 뒤 그 이미지의 CDN 주소 — 상품 등록의 이미지 url 에 이 값을 넣는다
 * @param expiresAt uploadUrl 이 만료되는 시각. 지나면 S3 가 403 이다 — 새로 발급받는다
 */
public record ImageUpload(String uploadUrl, Map<String, String> headers, String imageUrl, Instant expiresAt) {
}
