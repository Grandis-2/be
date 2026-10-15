package com.grandis.nova.catalog.image;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.exception.SdkException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * 관리자 사진 업로드 — 서버가 파일을 받아 검사 · 다시 인코딩한 뒤 S3 에 올리고 CDN 주소를 준다. 한 요청에 한 장.
 *
 * 순서: 검사(형식 · 크기 · 헤더의 가로 · 세로) → 동시 처리 허가 → 디코딩 · 다시 인코딩 → 이미지 행을 UNUSED 로 넣고 커밋 → S3 PutObject → 허가 반환.
 * 행을 먼저 넣는다 — S3 에 올린 뒤 행을 넣다 죽으면 표가 모르는 객체가 남는다. 반대로 행만 남은 경우(PUT 실패 · 결과를 모르는 시간 초과)는
 * 지우지 않는다 — 주소는 PUT 이 성공한 뒤에만 나가 아무도 붙이지 못하고, 정리 배치가 기준 시간 뒤 행 · 객체를 함께 지운다.
 *
 * 동시 처리는 {@link #MAX_CONCURRENT} 장 — 줄 세우지 않고 즉시 시도해, 못 받으면 {@link UploadBusyException}(503 + Retry-After)이다.
 * 허가는 S3 전송이 끝날 때까지 잡는다(전송 동안에도 다시 인코딩한 바이트를 힙에 든다).
 */
@Service
public class ImageUploadService {

    private static final Logger log = LoggerFactory.getLogger(ImageUploadService.class);

    /**
     * 동시에 디코딩 · 전송하는 장 수. 실측(JDK 25, 빈 JVM 에서 한 장을 처리하고 끝나는 최소 힙 — ExitOnOutOfMemoryError):
     * 픽셀 상한(2,500만)의 16비트 RGBA PNG 230MB · 방향을 돌리는 JPEG 180MB · 8비트 PNG 130MB. 운영 힙(태스크 메모리 2GB 의 75%)에서
     * 두 장이면 최악 약 460MB 다. 운영은 OutOfMemoryError 에서 프로세스를 끝내므로(Dockerfile) 늘리기 전에 다시 잰다.
     */
    static final int MAX_CONCURRENT = 2;
    /** 허가를 못 받았을 때 다시 시도하라는 초. 한 장의 처리는 1 ~ 2초다(위 실측의 디코딩 · 다시 인코딩). */
    static final int RETRY_AFTER_SECONDS = 2;

    private final ImageProcessor processor;
    private final ImageStore store;
    private final ImageStorage storage;
    private final ImageUploadProperties properties;
    private final Clock clock;
    private final Semaphore permits = new Semaphore(MAX_CONCURRENT);

    public ImageUploadService(ImageProcessor processor, ImageStore store, ImageStorage storage, ImageUploadProperties properties, Clock clock) {
        this.processor = processor;
        this.store = store;
        this.storage = storage;
        this.properties = properties;
        this.clock = clock;
    }

    /** 올린 사진의 CDN 주소. 검사에 어긋나면 400(file), 허가를 못 받으면 {@link UploadBusyException}, S3 가 실패하면 503. */
    public String upload(ImagePurpose purpose, MultipartFile file) {
        ImageProcessor.Inspected inspected = processor.inspect(file.getSize(), file);
        if (!permits.tryAcquire()) {
            throw new UploadBusyException(RETRY_AFTER_SECONDS);
        }
        try {
            // 파일을 힙에 올리는 것은 허가를 받은 뒤다 — 멀티파트는 디스크 임시 파일에 있다(file-size-threshold 0)
            byte[] encoded = processor.reencode(inspected, readAll(file));
            UUID id = UuidVersion7Strategy.INSTANCE.generateUuid(null);
            String key = ImageKeys.key(purpose, id, inspected.format());
            // 자동 커밋 — 호출자 트랜잭션 없이 부른다(PUT 전에 커밋돼 있어야 PUT 뒤 죽어도 표가 객체를 안다)
            store.insertUnused(id, key, clock.instant());
            try {
                storage.put(key, inspected.format(), encoded);
            } catch (SdkException e) {
                log.warn("사진 S3 업로드 실패(행은 UNUSED 로 남아 정리 배치가 지운다): key={}", key, e);
                throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE, "사진 저장소에 올리지 못했습니다.");
            }
            return properties.imageUrlPrefix() + key;
        } finally {
            permits.release();
        }
    }

    private static byte[] readAll(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
