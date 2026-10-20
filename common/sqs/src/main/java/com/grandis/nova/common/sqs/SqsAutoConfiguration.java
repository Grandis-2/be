package com.grandis.nova.common.sqs;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

/**
 * nova.sqs.region 이 있을 때만 SQS 클라이언트 · 큐 URL 을 만든다. 없으면 소비기를 만들 수 없고,
 * nova.outbox.transport=sqs 면 전송이 클라이언트를 찾지 못해 기동이 실패한다.
 *
 * 자동설정이라 서비스의 컴포넌트 스캔(com.grandis.nova)에서는 빠진다. 공통 클래스에는 @Component 를 붙이지 않는다.
 */
@AutoConfiguration
@EnableConfigurationProperties(SqsClientProperties.class)
public class SqsAutoConfiguration {

    /** 서비스가 SQS 에 쓸 HTTP 클라이언트를 둘 때의 빈 이름. 이름으로 찾아 다른 용도(S3 등)의 클라이언트를 집어 오지 않는다. */
    public static final String HTTP_CLIENT_BEAN = "sqsHttpClient";

    /**
     * HTTP 는 서비스가 {@link #HTTP_CLIENT_BEAN} 빈을 두면 그것으로(동시 호출이 많아 연결 수를 정해야 하는 서비스), 없으면 URLConnection
     * 으로 보낸다. 넘겨받은 HTTP 클라이언트는 이 클라이언트를 닫아도 닫히지 않는다 — 그 빈이 스스로 닫는다.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "nova.sqs", name = "region")
    SqsClient sqsClient(SqsClientProperties properties,
                        @Qualifier(HTTP_CLIENT_BEAN) ObjectProvider<SdkHttpClient> httpClient) {
        SqsClientBuilder builder = SqsClient.builder()
                .region(Region.of(properties.region()))
                .overrideConfiguration(config -> config.apiCallTimeout(properties.apiCallTimeout()));
        SdkHttpClient http = httpClient.getIfUnique();
        if (http == null) {
            builder.httpClientBuilder(UrlConnectionHttpClient.builder());
        } else {
            builder.httpClient(http);
        }
        if (properties.endpoint() == null) {
            return builder.credentialsProvider(DefaultCredentialsProvider.builder().build()).build();
        }
        return builder.endpointOverride(properties.endpoint())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "nova.sqs", name = "region")
    SqsQueueUrls sqsQueueUrls(SqsClient sqs, SqsClientProperties properties) {
        return new SqsQueueUrls(sqs, properties);
    }

    /**
     * 아웃박스 SQS 전송. common:outbox 가 클래스패스에 있을 때만 읽힌다 — 조건을 클래스 이름 문자열로 걸어,
     * 아웃박스 없이 SQS 만 쓰는 서비스에서 아웃박스 형을 읽지 않는다. 리스 길이와의 관계는 common:outbox 가 기동할 때 본다.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.grandis.nova.common.outbox.MessageTransport")
    static class OutboxTransport {

        @Bean
        @ConditionalOnMissingBean(type = "com.grandis.nova.common.outbox.MessageTransport")
        @ConditionalOnProperty(name = "nova.outbox.transport", havingValue = "sqs")
        SqsMessageTransport sqsMessageTransport(SqsClient sqs, SqsQueueUrls queueUrls, SqsClientProperties properties) {
            return new SqsMessageTransport(sqs, queueUrls, properties.apiCallTimeout());
        }
    }
}
