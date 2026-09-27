package com.grandis.nova.member.support;

import com.grandis.nova.member.TestKeys;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * 컨테이너 접속 정보와 시험용 서명 키를 컨텍스트에 넣는다. 테스트 클래스마다 @DynamicPropertySource 를 쓰지 않으려고 빈으로 둔다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MemberTestContext {

    /** 관리자 로그인을 태우는 시험이 쓰는 평문. 해시는 여기서 만들어 넣는다 — 소스에 해시를 박아 두면 무엇의 해시인지 알 수 없다. */
    public static final String ADMIN_PASSWORD = "correct horse battery staple";

    @Bean
    DynamicPropertyRegistrar memberTestProperties() {
        return registry -> {
            registry.add("spring.datasource.url", Containers.MYSQL::getJdbcUrl);
            registry.add("spring.datasource.username", Containers.MYSQL::getUsername);
            registry.add("spring.datasource.password", Containers.MYSQL::getPassword);
            registry.add("spring.data.redis.host", Containers::redisHost);
            registry.add("spring.data.redis.port", Containers::redisPort);
            // 비용 12 미만은 바인딩이 거부한다. 매 기동 한 번만 계산된다.
            registry.add("admin.password-hash", () -> new BCryptPasswordEncoder(12).encode(ADMIN_PASSWORD));
            TestKeys.register(registry);
        };
    }
}
