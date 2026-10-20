package com.grandis.nova.catalog.image;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** S3 가 연결은 받고 응답하지 않을 때 — 업로드가 시간 상한 안에 예외로 끝나 허가를 돌려줄 수 있는지 실제 클라이언트로 잰다. */
@DisplayName("사진 S3 클라이언트 — 응답 없는 서버에서 시도별 · 전체 시간 상한")
class ImageS3ClientTimeoutTest {

    @Test
    @DisplayName("시도 300ms · 전체 1s 면 1s 근처에서 SdkException — 그 안에서 재시도하고(연결 2번 이상), 응답을 끝없이 기다리지 않는다")
    void silentServerTimesOut() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        List<Socket> held = new ArrayList<>();
        try (ServerSocket server = new ServerSocket(0)) {
            Thread acceptor = Thread.ofPlatform().daemon().start(() -> {
                while (!server.isClosed()) {
                    try {
                        held.add(server.accept());  // 받기만 하고 아무것도 보내지 않는다
                        connections.incrementAndGet();
                    } catch (Exception e) {
                        return;
                    }
                }
            });
            ImageUploadProperties properties = new ImageUploadProperties("bucket", URI.create("https://img"), "ap-northeast-2",
                    URI.create("http://127.0.0.1:" + server.getLocalPort()), "test", "test", Duration.ofMillis(300), Duration.ofSeconds(1));
            try (S3Client s3 = new ImageUploadConfig().imageS3Client(properties)) {
                ImageStorage storage = new ImageStorage(s3, properties);
                long start = System.nanoTime();
                assertThatThrownBy(() -> storage.put("products/x.png", ImageFormat.PNG, new byte[1024])).isInstanceOf(SdkException.class);
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                System.out.println("=== elapsed " + elapsedMs + "ms, connections " + connections.get());
                assertThat(elapsedMs).isBetween(800L, 2_000L);
                assertThat(connections.get()).isGreaterThanOrEqualTo(2);
            }
            acceptor.interrupt();
        } finally {
            for (Socket s : held) {
                s.close();
            }
        }
    }
}
