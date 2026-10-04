package com.grandis.nova.preorder.integration.catalog;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.ImportHttpServices;

/** catalog 내부 API 클라이언트. 주소 · 타임아웃은 spring.http.serviceclient.catalog 설정으로 준다. */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = "catalog", types = CatalogClient.class)
class CatalogClientConfig {
}
