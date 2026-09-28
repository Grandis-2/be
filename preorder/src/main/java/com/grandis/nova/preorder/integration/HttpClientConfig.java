package com.grandis.nova.preorder.integration;

import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.integration.order.OrderClient;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.ImportHttpServices;

/**
 * 다른 서비스 내부 API 클라이언트. 주소 · 타임아웃은 spring.http.serviceclient.&lt;그룹&gt; 설정으로 준다.
 */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = "catalog", types = CatalogClient.class)
@ImportHttpServices(group = "order", types = OrderClient.class)
public class HttpClientConfig {
}
