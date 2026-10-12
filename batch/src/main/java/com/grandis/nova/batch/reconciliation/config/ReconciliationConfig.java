package com.grandis.nova.batch.reconciliation.config;

import com.grandis.nova.batch.reconciliation.mock.MockLedgerClient;
import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Mock 원장 클라이언트. RestClient.Builder 를 빈으로 내놓지 않고 완성된 클라이언트를 만든다 — 자동설정 빌더를 대체하지 않게(member 카카오와 같다).
 * 리다이렉트는 따라가지 않는다. 3xx 는 주소 설정 오류라 판정 불가로 드러나야 한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ReconciliationProperties.class, MockLedgerProperties.class})
class ReconciliationConfig {

    @Bean
    MockLedgerClient mockLedgerClient(MockLedgerProperties properties, JsonMapper json) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        RestClient rest = RestClient.builder().requestFactory(factory).baseUrl(properties.baseUrl().toString()).build();
        int maxBody = (int) Math.min(Integer.MAX_VALUE - 1L, properties.maxResponseBody().toBytes());
        return new MockLedgerClient(rest, json, properties.pageSize(), maxBody);
    }
}
