package com.grandis.nova.preorder.cancel.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 만료 설정은 작업이 꺼져 있어도 등록한다. 작업 빈의 조건과 떼어 둔다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentExpiryProperties.class)
class PaymentExpiryConfig {
}
