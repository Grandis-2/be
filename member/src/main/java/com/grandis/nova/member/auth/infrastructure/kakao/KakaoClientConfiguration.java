package com.grandis.nova.member.auth.infrastructure.kakao;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 카카오 호출용 RestClient. 타임아웃은 KakaoProperties(connect 2s · read 5s, nova 설정 예시와 같음).
 * RestClient.Builder 타입 빈을 내놓으면 Boot 의 자동설정 빌더(@ConditionalOnMissingBean)를 대체해 다른 HTTP 클라이언트가
 * 카카오 타임아웃을 물려받으므로, 완성된 RestClient 를 이름 붙여 내놓는다. JDK HttpClient 로 직접 건다 — Boot 의 요청 팩토리
 * 도우미는 4.x 에서 모듈이 옮겨져 의존을 하나 더 끌어야 한다. 테스트는 MockRestServiceServer 를 별도 빌더에 묶는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KakaoProperties.class)
public class KakaoClientConfiguration {

    @Bean
    RestClient kakaoRestClient(KakaoProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        return RestClient.builder().requestFactory(factory).build();
    }
}
