package com.grandis.nova.member.auth.infrastructure.db;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 만료 리프레시 정리({@link RefreshTokenCleanup})의 주기 실행과 설정. @EnableScheduling 은 member 의 다른 주기 작업도 함께 켜고, 그 작업들은 스레드 하나를 나눠 쓴다. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(RefreshTokenCleanup.Settings.class)
public class RefreshTokenCleanupConfiguration {
}
