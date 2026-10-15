package com.grandis.nova.catalog.image;

import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.MinioContainer;
import com.grandis.nova.common.UuidBinary;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 관리자 사진 업로드 — S3 호환 서버(MinIO)에 실제로 올리고, 올린 객체 · 이미지 표의 행까지 본다. */
@CatalogIntegrationTest
@AutoConfigureMockMvc
@DisplayName("관리자 · 사진 업로드")
class ImageUploadApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PATH = "/api/v1/admin/images";
    private static final String BUCKET = "catalog-upload-test";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void minio(DynamicPropertyRegistry registry) {
        registry.add("nova.upload.bucket", () -> BUCKET);
        registry.add("nova.upload.endpoint", MinioContainer::endpoint);
        registry.add("nova.upload.access-key", () -> MinioContainer.ACCESS_KEY);
        registry.add("nova.upload.secret-key", () -> MinioContainer.SECRET_KEY);
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
    @DisplayName("201 — 상품 PNG 는 products/{v7 id}.png 로 올라가고(형식 image/png, 다시 인코딩한 같은 크기 사진), 행은 그 id · 키로 UNUSED")
    void uploadsProductPng() throws Exception {
        Instant before = Instant.now();
        String imageUrl = imageUrl(upload(ImageFixtures.png(40, 30), "PRODUCT").andExpect(status().isCreated()));

        assertThat(imageUrl).matches("https://img/products/[0-9a-f-]{36}\\.png");
        String key = imageUrl.substring("https://img/".length());
        UUID id = ImageKeys.idOf(key).orElseThrow();
        assertThat(id.version()).isEqualTo(7);

        try (S3Client s3 = s3()) {
            ResponseBytes<GetObjectResponse> object = s3.getObjectAsBytes(b -> b.bucket(BUCKET).key(key));
            assertThat(object.response().contentType()).isEqualTo("image/png");
            BufferedImage stored = ImageIO.read(new ByteArrayInputStream(object.asByteArray()));
            assertThat(stored.getWidth()).isEqualTo(40);
            assertThat(stored.getHeight()).isEqualTo(30);
        }
        Map<String, Object> row = jdbc.queryForMap("SELECT object_key, status, created_at, updated_at FROM images WHERE id = ?", UuidBinary.toBytes(id));
        assertThat(row.get("object_key")).isEqualTo(key);
        assertThat(row.get("status")).isEqualTo("UNUSED");
        // 시각은 UTC 벽시계(시험 JVM 은 Asia/Seoul) — 배치가 같은 기준으로 기준 시각을 계산한다
        Instant updatedAt = ((LocalDateTime) row.get("updated_at")).toInstant(ZoneOffset.UTC);
        assertThat(updatedAt).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
        assertThat(row.get("created_at")).isEqualTo(row.get("updated_at"));
    }

    @Test
    @DisplayName("201 — 홍보 JPEG 는 promotions/{id}.jpg, 형식 image/jpeg")
    void uploadsPromotionJpeg() throws Exception {
        String imageUrl = imageUrl(upload(ImageFixtures.jpeg(20, 20), "PROMOTION").andExpect(status().isCreated()));
        assertThat(imageUrl).matches("https://img/promotions/[0-9a-f-]{36}\\.jpg");
        try (S3Client s3 = s3()) {
            assertThat(s3.headObject(b -> b.bucket(BUCKET).key(imageUrl.substring("https://img/".length()))).contentType()).isEqualTo("image/jpeg");
        }
    }

    @Test
    @DisplayName("파일 파트는 전체에서 정확히 하나, 이름은 file — 같은 이름 둘 · 다른 이름 파일 끼움 · 다른 이름만 · 없음은 400(file), 행을 넣지 않는다")
    void requiresExactlyOneFilePart() throws Exception {
        byte[] png = ImageFixtures.png(4, 4);
        long rows = countRows();
        send(multipart(PATH).file(part("file", png)).file(part("file", png)).param("purpose", "PRODUCT")).andExpect(fileRejected());
        send(multipart(PATH).file(part("file", png)).file(part("other", png)).param("purpose", "PRODUCT")).andExpect(fileRejected());
        send(multipart(PATH).file(part("image", png)).param("purpose", "PRODUCT")).andExpect(fileRejected());
        send(multipart(PATH).param("purpose", "PRODUCT")).andExpect(fileRejected());
        assertThat(countRows()).isEqualTo(rows);
    }

    @Test
    @DisplayName("사진이 아니면(이름 · Content-Type 이 image/png 여도) 400(file), 행 · 객체를 만들지 않는다")
    void rejectsNonImage() throws Exception {
        long rows = countRows();
        upload("<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes(), "PRODUCT").andExpect(fileRejected());
        upload(ImageFixtures.encode(ImageFixtures.rgb(4, 4), "gif"), "PRODUCT").andExpect(fileRejected());
        assertThat(countRows()).isEqualTo(rows);
    }

    @Test
    @DisplayName("purpose 가 없거나 모르는 값이면 400(purpose)")
    void requiresPurpose() throws Exception {
        byte[] png = ImageFixtures.png(4, 4);
        send(multipart(PATH).file(part("file", png))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[0].field").value("purpose"));
        upload(png, "REVIEW").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[0].field").value("purpose"));
    }

    @Test
    @DisplayName("관리자만 — 회원은 403, 토큰 없으면 401")
    void adminOnly() throws Exception {
        MockMultipartHttpServletRequestBuilder member = multipart(PATH);
        member.file(part("file", ImageFixtures.png(4, 4))).param("purpose", "PRODUCT");
        mockMvc.perform(member.with(user("657").roles("USER"))).andExpect(status().isForbidden());
        MockMultipartHttpServletRequestBuilder anonymous = multipart(PATH);
        anonymous.file(part("file", ImageFixtures.png(4, 4))).param("purpose", "PRODUCT");
        mockMvc.perform(anonymous).andExpect(status().isUnauthorized());
    }

    private ResultActions upload(byte[] file, String purpose) throws Exception {
        return send(multipart(PATH).file(part("file", file)).param("purpose", purpose));
    }

    private ResultActions send(MockMultipartHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(user("admin").roles("ADMIN")));
    }

    private static MockMultipartFile part(String name, byte[] bytes) {
        // 이름 · 형식은 프론트가 보내는 고정 값 — 서버는 둘 다 보지 않는다
        return new MockMultipartFile(name, "image", "image/png", bytes);
    }

    private static org.springframework.test.web.servlet.ResultMatcher fileRejected() {
        return result -> {
            status().isBadRequest().match(result);
            jsonPath("$.error.details.violations[0].field").value("file").match(result);
        };
    }

    private static String imageUrl(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString()).get("data").get("imageUrl").asString();
    }

    private long countRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM images", Long.class);
    }

    private static S3Client s3() {
        return S3Client.builder().region(Region.AP_NORTHEAST_2).endpointOverride(URI.create(MinioContainer.endpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(MinioContainer.ACCESS_KEY, MinioContainer.SECRET_KEY)))
                .forcePathStyle(true)
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }
}
