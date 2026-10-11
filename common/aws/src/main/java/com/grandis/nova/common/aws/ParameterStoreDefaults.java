package com.grandis.nova.common.aws;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Parameter Store 연동의 기본값. 운영 설정은 spring.config.import(aws-parameterstore:)로 기동할 때만 읽고,
 * 그 가져오기는 자기 클라이언트를 따로 만든다. 앱 안의 SsmClient 빈(설정 재적재 · 직접 조회용)은 쓰지 않으므로 기본으로 끈다 —
 * 켜 두면 AWS 리전이 없는 로컬 · CI 에서 그 빈을 만들다 기동이 실패한다. 가장 낮은 우선순위라 설정이 주면 그 값이 이긴다.
 */
class ParameterStoreDefaults implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE_NAME = "parameterStoreDefaults";
    static final String CLIENT_ENABLED = "spring.cloud.aws.parameterstore.enabled";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, Map.of(CLIENT_ENABLED, false)));
    }

    /** 설정 파일 · Parameter Store 를 모두 읽은 뒤에 돈다. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
