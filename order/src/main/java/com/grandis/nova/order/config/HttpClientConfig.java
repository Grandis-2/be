package com.grandis.nova.order.config;

import com.grandis.nova.order.client.payment.PaymentClient;
import com.grandis.nova.order.client.preorder.PreorderClient;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.ImportHttpServices;

/**
 * 다른 서비스 내부 API 클라이언트. 주소 · 타임아웃은 spring.http.serviceclient.&lt;그룹&gt; 설정으로 준다.
 */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = "preorder", types = PreorderClient.class)
@ImportHttpServices(group = "payment", types = PaymentClient.class)
public class HttpClientConfig {
}
