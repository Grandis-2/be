package com.grandis.nova.catalog.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * JVM 에 하나뿐인 MinIO — 사진 업로드가 실제로 올린 객체(형식 · 내용)를 보는 S3 호환 서버. 서명도 검사한다(Floci 는 서명을 보지 않는다 —
 * 실측: 서명을 바꾼 PUT 도 200). 테스트 컨텍스트가 닫혀도 멈추지 않는다.
 * 이미지는 bitnamilegacy 다 — minio/minio 는 Docker Hub 에서 받을 수 없다(실측: pull access denied).
 */
public final class MinioContainer {

    public static final String ACCESS_KEY = "catalogtest";
    public static final String SECRET_KEY = "catalogtest-secret";

    private static final GenericContainer<?> INSTANCE = start();

    private MinioContainer() {
    }

    public static String endpoint() {
        return "http://" + INSTANCE.getHost() + ":" + INSTANCE.getMappedPort(9000);
    }

    private static GenericContainer<?> start() {
        GenericContainer<?> container = new GenericContainer<>("bitnamilegacy/minio:2025.7.23")
                .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
                .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
        container.start();
        return container;
    }
}
