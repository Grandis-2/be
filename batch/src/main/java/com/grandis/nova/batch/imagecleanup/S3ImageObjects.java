package com.grandis.nova.batch.imagecleanup;

import java.time.Duration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/**
 * 업로드 버킷에서 사진 객체를 지운다(DeleteObject). 시도마다 · 재시도를 합친 전체에 시간 상한을 둔다 — 정리는 스케줄러 스레드를
 * 정합성 검사와 함께 쓰므로 S3 가 멈춰도 한 건이 상한 안에 끝나야 한다. 클라이언트 설정은 catalog 의 업로드와 같다.
 */
final class S3ImageObjects implements ImageObjects {

    /** 첫 시도를 합친 시도 수(재시도 2번). 없는 객체 삭제도 성공이라 다시 보내도 결과가 같다 */
    static final int MAX_ATTEMPTS = 3;
    static final Duration ATTEMPT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration CALL_TIMEOUT = Duration.ofSeconds(25);

    private final S3Client s3;
    private final String bucket;

    S3ImageObjects(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    /** 버킷이 없으면 기동하지 않는다 — 실제 삭제를 켰는데 지울 곳이 없으면 실행마다 실패하는 대신 배포에서 드러난다 */
    static S3ImageObjects create(UploadBucketProperties properties) {
        if (properties.bucket() == null || properties.bucket().isBlank()) {
            throw new IllegalArgumentException(
                    "nova.upload.bucket 이 없다 — nova.batch.image-cleanup.dry-run 을 끄려면 사진을 지울 업로드 버킷이 있어야 한다");
        }
        S3ClientBuilder builder = S3Client.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(config -> config
                        .apiCallAttemptTimeout(ATTEMPT_TIMEOUT)
                        .apiCallTimeout(CALL_TIMEOUT)
                        .retryStrategy(retry -> retry.maxAttempts(MAX_ATTEMPTS)));
        if (properties.region() != null && !properties.region().isBlank()) {
            builder.region(Region.of(properties.region()));
        }
        if (properties.endpoint() != null) {
            builder.endpointOverride(properties.endpoint())
                    .forcePathStyle(true)
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())));
        }
        return new S3ImageObjects(builder.build(), properties.bucket());
    }

    @Override
    public void delete(String objectKey) {
        s3.deleteObject(delete -> delete.bucket(bucket).key(objectKey));
    }

    @Override
    public void close() {
        s3.close();
    }
}
