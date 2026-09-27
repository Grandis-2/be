package com.grandis.nova.member;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 카카오 로그인 · JWT 발급 · 회원 정보. ECS desired 2~4.
 */
@SpringBootApplication(scanBasePackages = "com.grandis.nova")
public class MemberApplication {

    public static void main(String[] args) {
        SpringApplication.run(MemberApplication.class, args);
    }
}
