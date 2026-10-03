package com.grandis.nova.preorder.integration.order;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.ImportHttpServices;

/** order 내부 API 클라이언트. 주소 · 타임아웃은 spring.http.serviceclient.order 설정으로 준다. */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = "order", types = OrderClient.class)
class OrderClientConfig {
}
