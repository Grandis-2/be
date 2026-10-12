package com.grandis.nova.catalog.image;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** presigned URL 서명기. 서명은 버킷에 요청하지 않고 앱 안에서 한다(실측: 없는 버킷 · 가짜 자격 증명으로도 URL 이 나온다). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ImageUploadProperties.class)
class ImageUploadConfig {

    @Bean(destroyMethod = "close")
    S3Presigner imagePresigner(ImageUploadProperties properties) {
        S3Presigner.Builder builder = S3Presigner.builder();
        if (properties.region() != null && !properties.region().isBlank()) {
            builder.region(Region.of(properties.region()));
        }
        if (properties.endpoint() != null) {
            builder.endpointOverride(properties.endpoint())
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())));
        }
        return builder.build();
    }
}
