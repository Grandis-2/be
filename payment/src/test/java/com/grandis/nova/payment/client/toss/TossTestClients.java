package com.grandis.nova.payment.client.toss;

import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * MockRestServiceServer 에 묶인 클라이언트. 인증 헤더는 운영과 같은 {@link TossAuthorization} 으로 싣는다.
 * 주소 · 타임아웃 · 그룹 조립은 {@link TossClientWiringTest} 가 스프링 설정으로 따로 본다.
 */
final class TossTestClients {

    static final String BASE_URL = "https://api.tosspayments.com";
    // 테스트 가맹점 자격 증명 자리. 로그 · 예외 · toString 검사에 쓴다. 실제 키 모양이 아니다.
    static final String MERCHANT_CREDENTIAL = "nv98-client-test-credential";

    record Pair(MockRestServiceServer server, TossPaymentClient client) {
    }

    private TossTestClients() {
    }

    static Pair create() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        TossAuthorization.apply(builder, new TossProperties(MERCHANT_CREDENTIAL));
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TossPaymentsApi api = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(builder.build())).build()
                .createClient(TossPaymentsApi.class);
        return new Pair(server, new TossPaymentClient(api));
    }
}
