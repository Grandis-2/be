package com.grandis.nova.preorder.query.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PreorderQueryProperties.class)
class PreorderQueryConfig {
}
