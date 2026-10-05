package com.grandis.nova.waitingroom;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** 자기 패키지만 스캔한다. common 의 servlet 빈이 잡히면 리액티브 앱에 MVC 설정이 섞인다. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class WaitingroomApplication {

    public static void main(String[] args) {
        SpringApplication.run(WaitingroomApplication.class, args);
    }
}
