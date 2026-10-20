package com.grandis.nova.batch.imagecleanup;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 사진 정리 설정과 S3 클라이언트. dry-run 이면 S3 클라이언트를 만들지 않는다 — 리전을 못 찾으면 클라이언트 생성이 실패하므로, 버킷 ·
 * 리전 설정이 없는 로컬 · 시험에서도 뜨게 한다. dry-run 여부는 바인딩한 설정 하나로 가른다(조건부 빈과 설정 값이 어긋나지 않게).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ImageCleanupProperties.class, UploadBucketProperties.class})
class ImageCleanupConfig {

    /** 닫을 때 S3 클라이언트도 닫는다(@Bean 의 close 추론) */
    @Bean
    ImageObjects imageObjects(ImageCleanupProperties cleanup, UploadBucketProperties bucket) {
        return cleanup.dryRun() ? ImageObjects.DRY_RUN : S3ImageObjects.create(bucket);
    }
}
