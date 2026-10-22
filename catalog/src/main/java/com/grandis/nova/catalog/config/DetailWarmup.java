package com.grandis.nova.catalog.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 기동할 때 트래픽을 받기 전에 홍보 상세 · 상품 상세를 이 인스턴스에 직접 불러 데운다 — 새 JVM 은 처음 받는 경로의 클래스 적재 · JIT 컴파일 ·
 * 첫 연결에 몇 초를 쓰고, 그 사이 몰린 요청이 Redis 응답까지 늦춰(같은 CPU) 시간 초과 · 캐시 우회로 번졌다(부하 시험 10-22: 두 인스턴스 모두
 * 처음 3초 중앙값 0.18~0.28초(추적 표본), 그중 한 대만 Redis 시간 초과 뒤 무너짐).
 *
 * <ul>
 *   <li><b>시점</b>: ApplicationRunner — 웹 서버가 포트를 연 뒤, readiness 가 ACCEPTING_TRAFFIC 이 되기 전(Spring Boot 4.1 은 러너 ·
 *       ApplicationReadyEvent 처리가 끝난 뒤 readiness 를 바꾼다). ALB 는 readiness 를 보므로 예열이 끝날 때까지 이 인스턴스에 보내지 않는다.</li>
 *   <li><b>대상</b>: 오늘 보이는 홍보 목록 → 각 홍보 상세 → 그 카드의 상품 상세, 없으면 상품 목록 첫 쪽의 상세. 실제 HTTP 로 부른다(필터 · 보안 ·
 *       직렬화까지 같은 경로). 읽기 캐시도 이때 채워진다. 요청은 user-agent {@value #USER_AGENT} 로 남는다(요청 지표에는 섞인다).</li>
 *   <li><b>상한</b>: 대상을 돌아가며 {@link #MAX_REQUESTS} 번 또는 {@code nova.warmup.budget}(기본 {@link #DEFAULT_BUDGET}) 중 먼저 닿는 쪽까지.
 *       0 이하면 끈다. 실패(대상 없음 · 오류 응답 · 다른 서비스 무응답)는 INFO/WARN 만 남기고 기동은 이어 간다 — 예열이 배포를 막지 않게.</li>
 *   <li>웹 서버가 없는 실행(MockMvc 시험)에서는 하지 않는다.</li>
 * </ul>
 */
@Component
public class DetailWarmup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DetailWarmup.class);

    static final String USER_AGENT = "nova-warmup";
    static final Duration DEFAULT_BUDGET = Duration.ofSeconds(10);
    /** 예열 요청 수 상한. */
    static final int MAX_REQUESTS = 600;
    /** 상세를 부를 상품 수 상한 — 홍보가 많아도 한 상품을 여러 번 부르는 쪽이 JIT 에는 같다. */
    static final int MAX_PRODUCTS = 20;

    private final ApplicationContext context;
    private final JsonMapper json;
    private final Duration budget;

    public DetailWarmup(ApplicationContext context, JsonMapper json, @Value("${nova.warmup.budget:10s}") Duration budget) {
        this.context = context;
        this.json = json;
        this.budget = budget;
    }

    @Override
    public void run(ApplicationArguments args) {
        warm();
    }

    /** 예열 한 번 — 끈 경우 · 웹 서버가 없는 실행 · 대상이 없으면 요청 0. */
    Result warm() {
        if (budget.isNegative() || budget.isZero()) {
            return Result.NONE;
        }
        if (!(context instanceof WebServerApplicationContext web) || web.getWebServer() == null || web.getWebServer().getPort() <= 0) {
            return Result.NONE;
        }
        long started = System.nanoTime();
        long deadline = started + budget.toNanos();
        RestClient client = client(web.getWebServer().getPort());
        List<String> paths = targets(client, deadline);
        if (paths.isEmpty()) {
            log.info("상세 예열 대상이 없다 — 건너뛴다");
            return Result.NONE;
        }
        int sent = 0;
        int failed = 0;
        while (sent < MAX_REQUESTS && System.nanoTime() < deadline) {
            if (!get(client, paths.get(sent % paths.size()))) {
                failed++;
            }
            sent++;
        }
        log.info("상세 예열 끝 requests={} failed={} paths={} took={}ms", sent, failed, paths.size(),
                Duration.ofNanos(System.nanoTime() - started).toMillis());
        return new Result(sent, failed, List.copyOf(paths));
    }

    /** @param requests 대상 목록을 만든 조회를 뺀 예열 요청 수 */
    record Result(int requests, int failed, List<String> paths) {
        static final Result NONE = new Result(0, 0, List.of());
    }

    /** 홍보 상세들과 그 상품 상세들 — 홍보가 없으면 상품 목록 첫 쪽의 상세. */
    private List<String> targets(RestClient client, long deadline) {
        List<String> paths = new ArrayList<>();
        Set<String> products = new LinkedHashSet<>();
        for (JsonNode summary : items(client, "/api/v1/promotions")) {
            if (System.nanoTime() >= deadline) {
                break;
            }
            String promotion = "/api/v1/promotions/" + summary.path("promotionId").asString();
            paths.add(promotion);
            JsonNode detail = read(client, promotion);
            if (detail != null) {
                detail.path("data").path("products").forEach(card -> products.add(card.path("productId").asString()));
            }
        }
        if (products.isEmpty()) {
            items(client, "/api/v1/products?size=" + MAX_PRODUCTS).forEach(card -> products.add(card.path("productId").asString()));
        }
        products.stream().filter(id -> !id.isEmpty()).limit(MAX_PRODUCTS).forEach(id -> paths.add("/api/v1/products/" + id));
        return paths;
    }

    private List<JsonNode> items(RestClient client, String path) {
        JsonNode body = read(client, path);
        List<JsonNode> items = new ArrayList<>();
        if (body != null) {
            body.path("data").path("items").forEach(items::add);
        }
        return items;
    }

    private JsonNode read(RestClient client, String path) {
        try {
            String body = client.get().uri(path).retrieve().body(String.class);
            return body == null ? null : json.readTree(body);
        } catch (RuntimeException e) {
            log.warn("상세 예열 대상 조회 실패 path={}: {}", path, e.toString());
            return null;
        }
    }

    private static boolean get(RestClient client, String path) {
        try {
            client.get().uri(path).retrieve().toBodilessEntity();
            return true;
        } catch (RuntimeException e) {
            return false;   // 404(기간이 지난 홍보) · 다른 서비스 무응답 — 그 경로도 데워졌다
        }
    }

    private static RestClient client(int port) {
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());
        requests.setReadTimeout(Duration.ofSeconds(3));
        return RestClient.builder()
                .baseUrl("http://127.0.0.1:" + port)
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .requestFactory(requests)
                .build();
    }
}
