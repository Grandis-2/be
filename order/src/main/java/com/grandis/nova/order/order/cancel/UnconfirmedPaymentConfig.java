package com.grandis.nova.order.order.cancel;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UnconfirmedPaymentProperties.class)
class UnconfirmedPaymentConfig {
}
