package com.grandis.nova.preorder.integration.mock;

import com.grandis.nova.preorder.integration.DependencyGuard;
import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * 외부 예약 Mock 클라이언트. RestClient.Builder 를 빈으로 내놓지 않고 완성된 클라이언트를 만든다 — 내부 호출(catalog · order)의
 * 자동설정 빌더 · 토큰 전달에 섞이지 않게(batch 의 Mock 원장 클라이언트와 같다). 리다이렉트는 따라가지 않는다 — 3xx 는 주소 설정 오류다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ExternalMockProperties.class)
class ExternalMockClientConfig {

    @Bean
    ExternalMock externalMock(ExternalMockProperties properties, JsonMapper json, DependencyGuard guard) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        RestClient rest = RestClient.builder().requestFactory(factory).baseUrl(properties.baseUrl().toString()).build();
        int maxBody = (int) Math.min(Integer.MAX_VALUE, properties.maxResponseBody().toBytes());
        return new ExternalMock(rest, json, maxBody, guard);
    }
}
