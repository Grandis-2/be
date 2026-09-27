package com.grandis.nova.common.security;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 실제 MySQL·Redis 가 필요한 시험의 전제. 로컬에 없으면 건너뛰지만, CI(환경변수 CI 가 있으면)에서는 실패로 만든다 —
 * assumeTrue 는 실패가 아니라 중단이라 서비스 컨테이너가 빠져도 빌드가 초록인 채 그물이 빈다.
 */
public final class TestInfra {

    private TestInfra() {
    }

    public static void requirePort(int port, String what) {
        if (!reachable(port)) {
            unavailable("localhost:" + port + " 에 " + what + " 가 없다");
        }
    }

    /** 전제가 안 갖춰졌을 때. CI 면 실패, 아니면 skip. */
    public static void unavailable(String reason) {
        if (System.getenv("CI") != null) {
            throw new AssertionError(reason + " — CI 에서는 skip 이 아니라 실패다. 서비스 컨테이너를 확인하라");
        }
        assumeTrue(false, reason + " — 건너뛴다");
    }

    private static boolean reachable(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 300);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
