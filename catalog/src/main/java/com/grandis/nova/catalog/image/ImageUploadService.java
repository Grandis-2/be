package com.grandis.nova.catalog.image;

import com.grandis.nova.catalog.web.ValidationFailures;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 관리자 이미지 업로드 URL 발급. 파일은 서버를 거치지 않는다 — 브라우저가 presigned URL 로 S3 에 직접 PUT 하고, 조회는 CDN 으로 한다.
 * 형식(Content-Type)과 크기(Content-Length)를 서명에 넣어 S3 가 다른 파일을 거절하게 한다. 실측: SDK 가 서명하는 헤더가 content-length ·
 * content-type · host 이고, 서명을 검사하는 MinIO 에서 크기 · 형식이 다른 PUT 은 403(ImageUploadApiTest), 만료된 URL 의 PUT 도 403(1초 유효로
 * 발급한 일회성 프로브). AWS S3 자체에서는 재지 않았다.
 * 객체 키는 서버가 정한다(무작위 UUID) — 남이 올린 키를 덮어쓰는 업로드는 없다.
 */
@Service
public class ImageUploadService {

    /** 받는 형식 → 객체 키 확장자. */
    static final Map<String, String> EXTENSIONS = Map.of("image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");
    static final long MAX_BYTES = 10L * 1024 * 1024;
    /** 업로드 URL 유효 시간. 파일 하나를 고른 직후 올리는 데 쓴다. */
    static final Duration URL_TTL = Duration.ofMinutes(5);
    static final String KEY_PREFIX = "products/";

    private final S3Presigner presigner;
    private final ImageUploadProperties properties;

    public ImageUploadService(S3Presigner presigner, ImageUploadProperties properties) {
        this.presigner = presigner;
        this.properties = properties;
    }

    public ImageUpload prepare(ImageUploadRequest request) {
        String contentType = request.contentType().strip().toLowerCase(Locale.ROOT);
        String extension = EXTENSIONS.get(contentType);
        if (extension == null) {
            throw ValidationFailures.of("contentType", "image/jpeg · image/png · image/webp 만 올릴 수 있습니다.");
        }
        long length = request.contentLength();
        if (length < 1 || length > MAX_BYTES) {
            throw ValidationFailures.of("contentLength", "1 ~ %d 바이트(10MB)여야 합니다.".formatted(MAX_BYTES));
        }
        String key = KEY_PREFIX + UUID.randomUUID() + "." + extension;
        PresignedPutObjectRequest signed = presigner.presignPutObject(presign -> presign
                .signatureDuration(URL_TTL)
                .putObjectRequest(put -> put.bucket(properties.bucket()).key(key).contentType(contentType).contentLength(length)));
        return new ImageUpload(signed.url().toString(), Map.of("Content-Type", contentType),
                properties.imageUrlPrefix() + key, signed.expiration());
    }
}
