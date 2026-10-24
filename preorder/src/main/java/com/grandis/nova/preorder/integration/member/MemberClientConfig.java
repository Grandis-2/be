package com.grandis.nova.preorder.integration.member;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.ImportHttpServices;

/** member 내부 API 클라이언트. 주소 · 타임아웃은 spring.http.serviceclient.member 설정으로 준다(타임아웃 기본값은 PreorderDefaults). */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = "member", types = MemberClient.class)
class MemberClientConfig {
}
