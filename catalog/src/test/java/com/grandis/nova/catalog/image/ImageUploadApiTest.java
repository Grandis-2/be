package com.grandis.nova.catalog.image;

import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.MinioContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 사진 업로드 URL — 발급한 URL 로 서명을 검사하는 S3 호환 서버(MinIO)에 실제로 PUT 한다. 같은 파일은 받고, 발급 때와 다른 크기 · 형식은 거절되는지까지.
 */
@CatalogIntegrationTest
@AutoConfigureMockMvc
@DisplayName("관리자 · 상품 사진 업로드 URL")
class ImageUploadApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PATH = "/api/v1/admin/products/image-uploads";
    private static final String BUCKET = "catalog-upload-test";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired MockMvc mockMvc;

    @DynamicPropertySource
    static void minio(DynamicPropertyRegistry registry) {
        registry.add("nova.images.bucket", () -> BUCKET);
        registry.add("nova.images.endpoint", MinioContainer::endpoint);
        registry.add("nova.images.access-key", () -> MinioContainer.ACCESS_KEY);
        registry.add("nova.images.secret-key", () -> MinioContainer.SECRET_KEY);
    }

    @BeforeAll
    static void createBucket() {
        try (S3Client s3 = s3()) {
            if (s3.listBuckets().buckets().stream().noneMatch(b -> b.name().equals(BUCKET))) {
                s3.createBucket(b -> b.bucket(BUCKET));
            }
        }
    }

    @Test
    @DisplayName("201 — 발급한 URL 로 같은 형식 · 크기의 파일을 PUT 하면 올라가고, imageUrl 은 그 객체 키의 CDN 주소다")
    void issuedUrlAcceptsTheDeclaredFile() throws Exception {
        byte[] file = "png-bytes".getBytes();
        Instant before = Instant.now();
        JsonNode data = issue("image/png", file.length).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()
                .transform(JSON::readTree).get("data");

        URI uploadUrl = URI.create(data.get("uploadUrl").asString());
        String key = uploadUrl.getPath().substring(("/" + BUCKET + "/").length());
        assertThat(key).matches("products/[0-9a-f-]{36}\\.png");
        assertThat(data.get("imageUrl").asString()).isEqualTo("https://img/" + key);
        assertThat(data.get("headers").properties()).hasSize(1);
        assertThat(data.get("headers").get("Content-Type").asString()).isEqualTo("image/png");
        assertThat(Instant.parse(data.get("expiresAt").asString()))
                .isBetween(before.plus(ImageUploadService.URL_TTL).minusSeconds(1), Instant.now().plus(ImageUploadService.URL_TTL).plusSeconds(1));

        assertThat(put(uploadUrl, "image/png", file)).isEqualTo(200);
        try (S3Client s3 = s3()) {
            HeadObjectResponse head = s3.headObject(b -> b.bucket(BUCKET).key(key));
            assertThat(head.contentLength()).isEqualTo(file.length);
            assertThat(head.contentType()).isEqualTo("image/png");
        }
    }

    @Test
    @DisplayName("발급 때와 다른 크기 · 형식의 PUT 은 S3 가 403 으로 거절한다 — 크기 · 형식이 서명에 들어 있다")
    void putThatDiffersFromTheDeclaredFileIsRejected() throws Exception {
        URI uploadUrl = URI.create(issue("image/jpeg", 4).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString().transform(JSON::readTree).get("data").get("uploadUrl").asString());

        assertThat(put(uploadUrl, "image/jpeg", "abcdefgh".getBytes())).as("더 큰 파일").isEqualTo(403);
        assertThat(put(uploadUrl, "image/jpeg", "abc".getBytes())).as("더 작은 파일").isEqualTo(403);
        assertThat(put(uploadUrl, "text/html", "abcd".getBytes())).as("다른 형식").isEqualTo(403);
        assertThat(put(uploadUrl, "image/jpeg", "abcd".getBytes())).as("대조군 — 발급 그대로").isEqualTo(200);
    }

    @Test
    @DisplayName("형식은 jpeg · png · webp 만(대소문자 · 앞뒤 공백은 접는다), 크기는 1 ~ 10MiB — 경계는 받고 밖은 그 칸의 400")
    void typeAndSizeAreChecked() throws Exception {
        issue("image/webp", 1).andExpect(status().isCreated());
        issue(" IMAGE/JPEG ", ImageUploadService.MAX_BYTES).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.headers.Content-Type").value("image/jpeg"))
                .andExpect(jsonPath("$.data.imageUrl").value(org.hamcrest.Matchers.endsWith(".jpg")));

        issue("image/gif", 10).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[0].field").value("contentType"));
        issue("image/svg+xml", 10).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[0].field").value("contentType"));
        issue("image/png", 0).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[0].field").value("contentLength"));
        issue("image/png", ImageUploadService.MAX_BYTES + 1).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[0].field").value("contentLength"));
        send("{\"contentType\":\"image/png\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[0].field").value("contentLength"));
        send("{\"contentType\":\"image/png\",\"contentLength\":10,\"key\":\"x\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[0].field").value("key"));
    }

    @Test
    @DisplayName("관리자만 — 회원은 403, 토큰 없으면 401")
    void adminOnly() throws Exception {
        String body = "{\"contentType\":\"image/png\",\"contentLength\":10}";
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body).with(user("657").roles("USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
    }

    private ResultActions issue(String contentType, long contentLength) throws Exception {
        return send("{\"contentType\":\"%s\",\"contentLength\":%d}".formatted(contentType, contentLength));
    }

    private ResultActions send(String body) throws Exception {
        return mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body).with(user("admin").roles("ADMIN")));
    }

    private static int put(URI url, String contentType, byte[] body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(10)).header("Content-Type", contentType)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static S3Client s3() {
        return S3Client.builder().region(Region.AP_NORTHEAST_2).endpointOverride(URI.create(MinioContainer.endpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(MinioContainer.ACCESS_KEY, MinioContainer.SECRET_KEY)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }
}
