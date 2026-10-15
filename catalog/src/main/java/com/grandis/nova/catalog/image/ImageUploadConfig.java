package com.grandis.nova.catalog.image;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/**
 * 사진을 올리는 S3 클라이언트. 시도마다 · 재시도를 합친 전체에 시간 상한을 둔다 — S3 가 멈춰도 업로드가 상한 뒤에 503 으로 끝나고
 * 동시 처리 허가를 돌려준다. 전체 상한은 CloudFront 가 origin 응답을 기다리는 시간(60초)보다 짧아야 한다(디코딩 · 재인코딩 시간을 더해도).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ImageUploadProperties.class)
class ImageUploadConfig {

    /** 첫 시도를 합친 시도 수(재시도 2번). */
    static final int MAX_ATTEMPTS = 3;

    @Bean(destroyMethod = "close")
    S3Client imageS3Client(ImageUploadProperties properties) {
        S3ClientBuilder builder = S3Client.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(config -> config
                        .apiCallAttemptTimeout(properties.putAttemptTimeout())
                        .apiCallTimeout(properties.putTimeout())
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
        return builder.build();
    }
}
