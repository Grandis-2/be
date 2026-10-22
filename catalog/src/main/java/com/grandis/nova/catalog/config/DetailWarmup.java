package com.grandis.nova.catalog.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 *       직렬화까지 같은 경로). 읽기 캐시도 이때 채워진다.</li>
 *   <li><b>상한</b>: 대상을 돌아가며 {@link #MAX_REQUESTS} 번 또는 {@code nova.warmup.budget}(기본 {@link #DEFAULT_BUDGET}) 중 먼저 닿는 쪽까지.
 *       요청마다 제한 시간을 남은 시간으로 줄여 그 시간을 넘기지 않는다(로컬 연결 1초는 따로). 0 이하면 끈다. 실패(대상 없음 · 오류 응답 · 다른 서비스
 *       무응답 · 응답 모양이 다름)는 INFO/WARN 만 남기고 기동은 이어 간다 — 예열이 배포를 막지 않게.</li>
 *   <li><b>다른 서비스</b>: 상품 상세는 캐시와 상관없이 요청마다 실시간 칸을 묻는다(일반 상품은 order 재고, 사전예약은 회차 캐시를 거쳐 preorder).
 *       증설이 몰릴 때 그쪽에 더하지 않게 상품 상세는 {@link #MAX_PRODUCT_REQUESTS} 번까지만 부른다. 그쪽이 답하지 않으면 그 실패 로그도 그만큼 남는다.</li>
 *   <li><b>지표</b>: 요청은 user-agent {@value #USER_AGENT} 로 들어와 추적에서는 가를 수 있지만, 요청 지표(http_server_request_duration — 관측 에이전트가
 *       Tomcat 에서 잰다)에는 섞인다. 배포 · 증설 때마다 인스턴스당 최대 {@link #MAX_REQUESTS} 건의 막 뜬 JVM 요청이 홍보 · 상품 상세 경로의 지연
 *       분포에 들어가므로, 그 직후 p99 는 예열 요청을 뺀 추적으로 다시 본다.</li>
 *   <li>웹 서버가 없는 실행(MockMvc 시험)에서는 하지 않는다.</li>
 * </ul>
 * 로컬 재현(운영 이미지 · 관측 에이전트 포함 · CPU 0.5 · 막 뜬 catalog 에 홍보 상세 초당 120건 45초, 조건마다 3회)에서 예열을 끄면 1회가 무너졌고
 * (CPU 상한에 내내 걸림 · Redis 시간 초과 438건) 시간 초과는 41 · 0 · 438건, 켜면 무너짐 없이 9 · 0 · 0건이었다. 그 CPU 에서 예열은 매번 10초 상한에
 * 닿았다(158~483건). 요청 수 상한은 빠른 장비에서 10초를 다 쓰지 않게 둔다(시험 JVM 에서 600건이 2~3초).
 */
@Component
public class DetailWarmup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DetailWarmup.class);

    static final String USER_AGENT = "nova-warmup";
    static final Duration DEFAULT_BUDGET = Duration.ofSeconds(10);
    /** 예열 요청 수 상한 — 느린 장비는 시간 상한이 먼저 닿는다(위 실측). */
    static final int MAX_REQUESTS = 600;
    /** 상세를 부를 상품 수 상한 — 홍보가 많아도 한 상품을 여러 번 부르는 쪽이 JIT 에는 같다. */
    static final int MAX_PRODUCTS = 20;
    /** 상품 상세 요청 수 상한 — 요청마다 order 재고 · 회차를 묻는다(위 다른 서비스). */
    static final int MAX_PRODUCT_REQUESTS = 150;
    /** 요청 하나의 제한 시간 상한 — 남은 시간이 더 짧으면 그만큼. */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);

    private static final String PRODUCT_PREFIX = "/api/v1/products/";

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
        try {
            warm();
        } catch (RuntimeException e) {
            log.warn("상세 예열 실패 — 예열 없이 기동한다", e);
        }
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
        Calls calls = new Calls(web.getWebServer().getPort(), started + budget.toNanos());
        try (calls) {
            List<String> paths = targets(calls);
            if (paths.isEmpty()) {
                log.info("상세 예열 대상이 없다 — 건너뛴다");
                return Result.NONE;
            }
            int sent = 0;
            int failed = 0;
            int productRequests = 0;
            for (int i = 0; sent < MAX_REQUESTS && calls.timeLeft() != null; i++) {
                String path = paths.get(i % paths.size());
                boolean product = path.startsWith(PRODUCT_PREFIX);
                if (product && productRequests >= MAX_PRODUCT_REQUESTS) {
                    if (paths.stream().allMatch(p -> p.startsWith(PRODUCT_PREFIX))) {
                        break;   // 남은 대상이 모두 상품 상세다
                    }
                    continue;
                }
                if (calls.get(path) == null) {
                    failed++;
                }
                sent++;
                if (product) {
                    productRequests++;
                }
            }
            log.info("상세 예열 끝 requests={} productRequests={} failed={} paths={} took={}ms", sent, productRequests, failed, paths.size(),
                    Duration.ofNanos(System.nanoTime() - started).toMillis());
            return new Result(sent, productRequests, failed, List.copyOf(paths));
        }
    }

    /** @param requests 대상 목록을 만든 조회를 뺀 예열 요청 수(상품 상세 포함) */
    record Result(int requests, int productRequests, int failed, List<String> paths) {
        static final Result NONE = new Result(0, 0, 0, List.of());
    }

    /** 홍보 상세들과 그 상품 상세들 — 홍보가 없으면 상품 목록 첫 쪽의 상세. */
    private List<String> targets(Calls calls) {
        List<String> paths = new ArrayList<>();
        Set<String> products = new LinkedHashSet<>();
        for (JsonNode summary : items(calls, "/api/v1/promotions")) {
            String id = text(summary.path("promotionId"));
            if (id.isEmpty() || calls.timeLeft() == null) {
                continue;
            }
            String promotion = "/api/v1/promotions/" + id;
            paths.add(promotion);
            JsonNode detail = read(calls, promotion);
            if (detail != null) {
                detail.path("data").path("products").forEach(card -> products.add(text(card.path("productId"))));
            }
        }
        if (products.isEmpty() && calls.timeLeft() != null) {
            items(calls, "/api/v1/products?size=" + MAX_PRODUCTS).forEach(card -> products.add(text(card.path("productId"))));
        }
        products.stream().filter(id -> !id.isEmpty()).limit(MAX_PRODUCTS).forEach(id -> paths.add(PRODUCT_PREFIX + id));
        return paths;
    }

    /** 문자열 칸이면 그 값, 아니면(없음 · null · 객체 · 배열) 빈 문자열 — 응답 모양이 어긋나도 던지지 않는다. */
    private static String text(JsonNode node) {
        return node.isString() ? node.asString() : "";
    }

    private List<JsonNode> items(Calls calls, String path) {
        JsonNode body = read(calls, path);
        List<JsonNode> items = new ArrayList<>();
        if (body != null) {
            body.path("data").path("items").forEach(items::add);
        }
        return items;
    }

    private JsonNode read(Calls calls, String path) {
        String body = calls.get(path);
        if (body == null) {
            log.warn("상세 예열 대상 조회 실패 path={}", path);
            return null;
        }
        try {
            return json.readTree(body);
        } catch (RuntimeException e) {
            log.warn("상세 예열 대상 응답을 못 읽었다 path={}: {}", path, e.toString());
            return null;
        }
    }

    /** 이 인스턴스로 보내는 요청 — 마감까지 남은 시간으로 요청마다 제한 시간을 줄인다. */
    private static final class Calls implements AutoCloseable {

        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        private final String base;
        private final long deadline;

        private Calls(int port, long deadline) {
            this.base = "http://127.0.0.1:" + port;
            this.deadline = deadline;
        }

        /** 남은 시간(요청 하나의 상한 이하) — 마감이 지났으면 null. */
        Duration timeLeft() {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return null;
            }
            return Duration.ofNanos(Math.min(left, REQUEST_TIMEOUT.toNanos()));
        }

        /** 2xx 응답 본문, 아니면(오류 응답 · 시간 초과 · 마감 지남) null. */
        String get(String path) {
            Duration left = timeLeft();
            if (left == null) {
                return null;
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + path)).timeout(left).header("User-Agent", USER_AGENT).GET().build();
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                return response.statusCode() / 100 == 2 ? response.body() : null;   // 404(기간이 지난 홍보) · 503 도 그 경로는 데워졌다
            } catch (IOException e) {
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        @Override
        public void close() {
            client.close();
        }
    }
}
