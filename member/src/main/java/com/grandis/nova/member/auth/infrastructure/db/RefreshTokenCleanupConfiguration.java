package com.grandis.nova.member.auth.infrastructure.db;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 만료 리프레시 정리({@link RefreshTokenCleanup})의 주기 실행과 설정. member 에서 주기 작업은 이것 하나다. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(RefreshTokenCleanup.Settings.class)
public class RefreshTokenCleanupConfiguration {
}
