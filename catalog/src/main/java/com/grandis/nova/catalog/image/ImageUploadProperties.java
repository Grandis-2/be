package com.grandis.nova.catalog.image;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;

/**
 * 관리자 이미지 업로드(S3 presigned PUT) 설정. bucket · cdn-base-url 이 없으면 기동하지 않는다 — 업로드 API 가 조용히 500 이 되거나
 * 상품 등록의 이미지 주소 검사가 아무 주소나 받게 열리는 대신 배포에서 드러난다.
 *
 * @param bucket     업로드 버킷 이름. 브라우저가 presigned URL 로 여기에 직접 PUT 한다
 * @param cdnBaseUrl 그 버킷을 내놓는 CDN 주소(https, 경로 없음). 이미지 주소는 이 주소 + "/" + 객체 키다. 상품 등록은 이 주소 아래 이미지만 받는다
 * @param region     버킷 리전. 비우면 AWS 기본 리전 조회(환경 변수 AWS_REGION — 배포 환경은 인프라가 태스크에 넣는다)를 쓰고, 그것도 없으면 기동 실패
 * @param endpoint   로컬 S3 에뮬레이터 주소 — 로컬 · 시험 전용이다. 정하면 accessKey · secretKey 로 정적 자격 증명과 경로 방식 주소를 쓰고,
 *                   비우면 AWS 기본 주소와 기본 자격 증명(태스크 역할)을 쓴다. 배포 환경은 비운다
 */
@ConfigurationProperties("nova.images")
public record ImageUploadProperties(
        String bucket,
        URI cdnBaseUrl,
        String region,
        URI endpoint,
        @DefaultValue("test") String accessKey,
        @DefaultValue("test") String secretKey
) {

    public ImageUploadProperties {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("nova.images.bucket is required (the upload bucket browsers PUT images into)");
        }
        if (bucket.startsWith("CHANGE_ME")) {
            throw new IllegalArgumentException("nova.images.bucket is still the example placeholder");
        }
        if (cdnBaseUrl == null || !"https".equals(cdnBaseUrl.getScheme()) || cdnBaseUrl.getHost() == null
                || cdnBaseUrl.getRawUserInfo() != null || cdnBaseUrl.getRawQuery() != null || cdnBaseUrl.getRawFragment() != null
                || !(cdnBaseUrl.getRawPath() == null || cdnBaseUrl.getRawPath().isEmpty() || cdnBaseUrl.getRawPath().equals("/"))) {
            throw new IllegalArgumentException("nova.images.cdn-base-url must be an https origin without user info, path or query (e.g. https://cdn.example.com): " + cdnBaseUrl);
        }
    }

    /** 이 주소 아래의 이미지 주소가 시작하는 접두. 끝이 "/" 다. */
    public String imageUrlPrefix() {
        return "https://" + cdnBaseUrl.getRawAuthority() + "/";
    }
}
