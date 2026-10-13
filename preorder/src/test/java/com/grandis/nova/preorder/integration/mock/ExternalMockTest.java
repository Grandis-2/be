package com.grandis.nova.preorder.integration.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grandis.nova.preorder.support.FakeExternalMock;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 설정(ExternalMockClientConfig)이 만든 클라이언트로 가짜 Mock 을 부른다. 응답 해석과 실패 문구를 본다 */
class ExternalMockTest {

    private static final FakeExternalMock MOCK = FakeExternalMock.start();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ExternalMock externalMock = client(MOCK.url());

    @BeforeEach
    void setUp() {
        MOCK.reset();
    }

    @AfterAll
    static void tearDown() {
        MOCK.close();
    }

    @Test
    void 키로_조회는_활성_취소_기록_없음을_가른다() {
        MOCK.active("k-active");
        MOCK.canceled("k-canceled");

        assertThat(externalMock.lookup("k-active")).isEqualTo(MockKeyState.ACTIVE);
        assertThat(externalMock.lookup("k-canceled")).isEqualTo(MockKeyState.CANCELED);
        assertThat(externalMock.lookup("k-none")).isEqualTo(MockKeyState.ABSENT);
    }

    @Test
    void 키로_취소는_번호_없이_사유를_실어_보낸다() {
        MOCK.active("k-1");

        externalMock.cancel("k-1", ExternalMock.GHOST_COMPENSATION);

        JsonNode sent = MOCK.cancels().getFirst();
        assertThat(sent.get("externalKey").asString()).isEqualTo("k-1");
        assertThat(sent.get("reservationNo").isNull()).isTrue();
        assertThat(sent.get("reason").asString()).isEqualTo("GHOST_COMPENSATION");
        assertThat(MOCK.state("k-1")).isEqualTo("CANCELED");
    }

    @Test
    void 취소가_200_이_아니면_계약과_다름으로_던진다() {
        MOCK.cancelAnswers(503);

        assertThatThrownBy(() -> externalMock.cancel("k-1", ExternalMock.GHOST_COMPENSATION))
                .isInstanceOf(ExternalMockUnavailable.class)
                .hasMessage("Mock 키로 취소 응답이 계약과 다름(HTTP 503)");
    }

    @Test
    void 경로가_없는_404_는_기록_없음이_아니다() {
        ExternalMock wrongPath = client(MOCK.url() + "/wrong");

        assertThatThrownBy(() -> wrongPath.lookup("k-1"))
                .isInstanceOf(ExternalMockUnavailable.class)
                .hasMessage("Mock 키로 조회 응답이 계약과 다름(HTTP 404)");
    }

    @Test
    void 닿지_않으면_연결_실패로_던지고_주소는_싣지_않는다() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        ExternalMock unreachable = client("http://localhost:" + closedPort);

        assertThatThrownBy(() -> unreachable.lookup("k-1"))
                .isInstanceOf(ExternalMockUnavailable.class)
                .hasMessage("Mock 키로 조회 응답을 받지 못함 — 연결 실패");
    }

    private static ExternalMock client(String baseUrl) {
        ExternalMockProperties properties = new ExternalMockProperties(URI.create(baseUrl), Duration.ofMillis(300),
                Duration.ofSeconds(2), DataSize.ofKilobytes(64));
        return new ExternalMockClientConfig().externalMock(properties, JSON);
    }
}
