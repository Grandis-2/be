package com.grandis.nova.batch.imagecleanup;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 지울 사진이 있는 업로드 버킷 — catalog 가 사진을 올리는 버킷이라 설정 이름도 catalog 와 같다(nova.upload). dry-run 을 끌 때만 쓴다.
 * 배포 환경은 인프라가 bucket · region 을 넣고, 자격 증명은 태스크 역할(업로드 버킷 DeleteObject 권한)을 쓴다.
 *
 * @param bucket    업로드 버킷 이름. dry-run 을 끄면 반드시 있어야 한다
 * @param region    버킷 리전. 비우면 AWS 기본 리전 조회(환경 변수 AWS_REGION)를 쓰고, 그것도 없으면 기동 실패
 * @param endpoint  로컬 S3 에뮬레이터 주소 — 로컬 · 시험 전용이다. 정하면 accessKey · secretKey 로 정적 자격 증명과 경로 방식 주소를 쓰고,
 *                  비우면 AWS 기본 주소와 기본 자격 증명(태스크 역할)을 쓴다. 배포 환경은 비운다
 */
@ConfigurationProperties("nova.upload")
public record UploadBucketProperties(
        String bucket,
        String region,
        URI endpoint,
        @DefaultValue("test") String accessKey,
        @DefaultValue("test") String secretKey
) {
}
