package com.grandis.nova.member;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * 카카오 로그인 · JWT 발급 · 관리자 로그인 · 회원. ECS desired 2~4.
 * JPA Auditing 을 켜야 BaseEntity 의 created_at/updated_at 이 채워진다. 안 켜면 NOT NULL 에 걸려서야 드러난다.
 */
@SpringBootApplication(scanBasePackages = "com.grandis.nova")
@EnableJpaAuditing
public class MemberApplication {

    public static void main(String[] args) {
        SpringApplication.run(MemberApplication.class, args);
    }
}
