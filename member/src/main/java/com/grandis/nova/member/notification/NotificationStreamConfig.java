package com.grandis.nova.member.notification;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 안 읽은 수 실시간 연결({@link NotificationStreams})의 설정. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationStreamProperties.class)
class NotificationStreamConfig {
}
