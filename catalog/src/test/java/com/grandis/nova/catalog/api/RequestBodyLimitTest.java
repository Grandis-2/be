package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.support.AccessTokens;
import com.grandis.nova.catalog.web.RegistrationBodyLimitFilter;
import com.grandis.nova.catalog.support.MySqlContainerConfig;
import com.grandis.nova.catalog.support.RedisContainerConfig;
import com.grandis.nova.catalog.support.SecurityTestConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 본문 크기 상한 — 실제 Tomcat 으로 잰다(MockMvc 는 멀티파트를 파싱하지 않고 chunked 전송도 없다).
 * 사진 업로드의 멀티파트 상한(CatalogDefaults)과 상품 등록 JSON 상한(RegistrationBodyLimitFilter). 넘친 요청은 컨트롤러 전에 413 이고
 * 봉투의 코드는 VALIDATION_FAILED 다(프론트는 상태 413 으로 가른다).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_READ_COMMITTED",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
        "spring.flyway.locations=filesystem:${nova.migrations-path}",
        "spring.flyway.default-schema=shop",
        "spring.flyway.schemas=shop",
        "spring.flyway.create-schemas=false",
        "spring.flyway.clean-disabled=true",
        "spring.flyway.validate-migration-naming=true",
        "spring.threads.virtual.enabled=true",
        "jwt.issuer=" + AccessTokens.ISSUER,
        "spring.data.redis.timeout=300ms",
        "spring.data.redis.connect-timeout=200ms",
        "nova.outbox.transport=log",
        "nova.outbox.log-transport-allowed=true",
        "nova.outbox.relay-interval=1h",
        "nova.upload.bucket=catalog-test-images",
        "nova.upload.cdn-base-url=https://img",
        "nova.upload.region=ap-northeast-2",
        "management.server.port=0"
})
@Import({MySqlContainerConfig.class, RedisContainerConfig.class, SecurityTestConfig.class})
@DisplayName("본문 크기 상한 — 실제 서버에서 413")
class RequestBodyLimitTest {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String BOUNDARY = "nova-test-boundary";
    private static final int MIB = 1024 * 1024;

    @Value("${local.server.port}") int port;

    @Test
    @DisplayName("파일 10MiB 는 상한 안이라 컨트롤러가 본다(가짜 JPEG → 400 file), 10MiB + 1 바이트는 컨트롤러 전에 413 VALIDATION_FAILED")
    void fileSizeLimit() throws Exception {
        HttpResponse<String> atLimit = send(body(fakeJpeg(10 * MIB), 1));
        assertThat(atLimit.statusCode()).isEqualTo(400);
        assertThat(atLimit.body()).contains("\"field\":\"file\"");

        HttpResponse<String> over = send(body(fakeJpeg(10 * MIB + 1), 1));
        assertThat(over.statusCode()).isEqualTo(413);
        JsonNode error = JSON.readTree(over.body()).get("error");
        assertThat(error.get("code").asString()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    @DisplayName("파트 수 상한(5) — purpose · file 에 일반 칸을 더해 5 개면 컨트롤러가 보고, 6 개면 413")
    void partCountLimit() throws Exception {
        byte[] small = fakeJpeg(16);
        assertThat(send(body(small, 3)).statusCode()).as("파트 5개").isEqualTo(400);
        assertThat(send(body(small, 4)).statusCode()).as("파트 6개").isEqualTo(413);
    }

    @Test
    @DisplayName("상품 등록 JSON — Content-Length 가 256KiB 면 컨트롤러가 보고(깨진 JSON 400), 256KiB + 1 이면 413 VALIDATION_FAILED")
    void registrationBodyLimitByContentLength() throws Exception {
        HttpResponse<String> atLimit = register("/api/v1/admin/products", HttpRequest.BodyPublishers.ofByteArray(spaces(RegistrationBodyLimitFilter.MAX_BYTES)));
        assertThat(atLimit.statusCode()).isEqualTo(400);

        HttpResponse<String> over = register("/api/v1/admin/products", HttpRequest.BodyPublishers.ofByteArray(spaces(RegistrationBodyLimitFilter.MAX_BYTES + 1)));
        assertThat(over.statusCode()).isEqualTo(413);
        assertThat(JSON.readTree(over.body()).get("error").get("code").asString()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    @DisplayName("상품 등록 JSON — chunked(길이 없음)도 256KiB + 1 이면 413, 작은 본문은 그대로 컨트롤러에 간다(모르는 칸 x 의 400)")
    void registrationBodyLimitWhenChunked() throws Exception {
        HttpResponse<String> over = register("/api/v1/admin/products", chunked(spaces(RegistrationBodyLimitFilter.MAX_BYTES + 1)));
        assertThat(over.statusCode()).isEqualTo(413);

        HttpResponse<String> small = register("/api/v1/admin/products", chunked("{ \"x\": 1 }".getBytes(StandardCharsets.UTF_8)));
        assertThat(small.statusCode()).isEqualTo(400);
        assertThat(small.body()).contains("\"field\":\"x\"");
    }

    @Test
    @DisplayName("상품 등록 JSON — 퍼센트 인코딩(%70roducts)도 같은 경로로 보고 413(대조군: 그 경로의 작은 본문은 등록 컨트롤러가 받는다)")
    void registrationBodyLimitSeesTheDecodedPath() throws Exception {
        String encoded = "/api/v1/admin/%70roducts";
        assertThat(register(encoded, HttpRequest.BodyPublishers.ofString("{ \"x\": 1 }")).body()).as("대조군").contains("\"field\":\"x\"");
        assertThat(register(encoded, HttpRequest.BodyPublishers.ofByteArray(spaces(RegistrationBodyLimitFilter.MAX_BYTES + 1))).statusCode()).isEqualTo(413);
    }

    @Test
    @DisplayName("경로 매개변수(;x=1)를 붙인 등록은 보안 방화벽이 컨트롤러 전에 400 으로 거절한다 — 작은 본문도 등록에 닿지 않는다(봉투가 아닌 거절)")
    void pathParametersNeverReachRegistration() throws Exception {
        HttpResponse<String> small = register("/api/v1/admin/products;x=1", HttpRequest.BodyPublishers.ofString("{ \"x\": 1 }"));
        assertThat(small.statusCode()).isEqualTo(400);
        assertThat(small.body()).doesNotContain("\"field\":\"x\"");
    }

    private HttpResponse<String> register(String path, HttpRequest.BodyPublisher body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + AccessTokens.adminToken())
                .header("Idempotency-Key", "k-limit")
                .header("Content-Type", "application/json")
                .POST(body).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 길이를 알리지 않는 본문 — HttpClient 는 Transfer-Encoding: chunked 로 보낸다. */
    private static HttpRequest.BodyPublisher chunked(byte[] bytes) {
        return HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(bytes));
    }

    private static byte[] spaces(int size) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte) ' ');
        return bytes;
    }

    /** 시그니처만 JPEG 인 바이트 — 크기 검사는 통과하고 헤더 읽기에서 400 이다. */
    private static byte[] fakeJpeg(int size) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte) 1);
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        return bytes;
    }

    /** purpose · file 에 extraFields 개의 일반 칸을 더한 멀티파트 본문. */
    private static byte[] body(byte[] file, int extraFields) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        field(out, "purpose", "PRODUCT");
        for (int i = 0; i < extraFields; i++) {
            field(out, "extra" + i, "x");
        }
        out.writeBytes(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"image\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(file);
        out.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static void field(ByteArrayOutputStream out, String name, String value) {
        out.writeBytes(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> send(byte[] body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/admin/images"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + AccessTokens.adminToken())
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
