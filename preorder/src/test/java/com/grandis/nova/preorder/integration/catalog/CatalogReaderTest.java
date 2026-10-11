package com.grandis.nova.preorder.integration.catalog;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.security.AuthenticatedPrincipal;
import com.grandis.nova.common.security.NovaAuthentication;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.common.testing.Concurrently.Outcome;
import com.grandis.nova.common.testing.Concurrently;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.preorder.support.CatalogStubs;
import com.grandis.nova.preorder.support.DependencyGuards;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogReaderTest {

    static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-7000-8000-000000000007");
    static final UUID OPTION_ID = UUID.fromString("00000000-0000-7000-8000-000000000070");
    static final String TOKEN = "member-token";

    final MovableClock clock = new MovableClock();
    /** 다시 받기를 바로 돌리지 않고 모아 두었다가 시험이 정한 때에 돌린다. */
    final Queue<Runnable> refreshes = new ArrayDeque<>();

    @Test
    void 같은_상품은_한_번만_부르고_이후는_캐시에서_읽는다() {
        FakeCatalogClient client = new FakeCatalogClient();
        CatalogReader reader = reader(client);

        assertThat(reader.findOption(PRODUCT_ID, OPTION_ID)).get()
                .extracting(OptionSnapshot::optionTitle).isEqualTo("블랙 / 256GB");
        assertThat(reader.findOption(PRODUCT_ID, OPTION_ID)).isPresent();

        assertThat(client.calls.get()).isEqualTo(1);
    }

    @Test
    void 오픈_순간_동시에_몰려도_catalog_는_한_번만_부른다() throws Exception {
        FakeCatalogClient client = new FakeCatalogClient();
        client.delayMillis = 200;
        CatalogReader reader = reader(client);
        int requests = 20;

        List<Outcome<Boolean>> outcomes = Concurrently.run(requests, i -> () ->
                reader.findOption(PRODUCT_ID, OPTION_ID).isPresent());

        assertThat(outcomes).allMatch(o -> o.succeeded() && o.value());

        assertThat(client.calls.get()).isEqualTo(1);
    }

    @Test
    void 그_상품의_옵션이_아니면_비어_있다() {
        CatalogReader reader = reader(new FakeCatalogClient());

        assertThat(reader.findOption(PRODUCT_ID, UUID.randomUUID())).isEmpty();
    }

    @Test
    void 없는_상품이면_비어_있다() {
        FakeCatalogClient client = new FakeCatalogClient();
        client.notFound = true;

        assertThat(reader(client).findOption(PRODUCT_ID, OPTION_ID)).isEmpty();
    }

    @Test
    void 캐시에_없는데_catalog_가_응답하지_않으면_503_으로_알린다() {
        FakeCatalogClient client = new FakeCatalogClient();
        client.unavailable = true;

        assertThatThrownBy(() -> reader(client).findOption(PRODUCT_ID, OPTION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
    }

    @Test
    void 캐시에_없는데_catalog_가_5xx_면_503_으로_알린다() {
        FakeCatalogClient client = new FakeCatalogClient();
        client.errorStatus = HttpStatus.SERVICE_UNAVAILABLE;

        assertThatThrownBy(() -> reader(client).findOption(PRODUCT_ID, OPTION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
    }

    @Test
    void 없는_상품_외의_4xx_는_재시도_안내가_아니라_연동_오류다() {
        FakeCatalogClient client = new FakeCatalogClient();
        client.errorStatus = HttpStatus.BAD_REQUEST;
        CatalogReader reader = reader(client);

        assertThatThrownBy(() -> reader.findOption(PRODUCT_ID, OPTION_ID))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(BusinessException.class);

        client.errorStatus = null;
        assertThat(reader.findOption(PRODUCT_ID, OPTION_ID)).as("실패는 캐시하지 않는다").isPresent();
    }

    @Test
    void 읽을_수_없는_응답은_재시도_안내가_아니라_연동_오류다() {
        FakeCatalogClient client = new FakeCatalogClient();
        client.unreadable = true;

        assertThatThrownBy(() -> reader(client).findOption(PRODUCT_ID, OPTION_ID))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(BusinessException.class);
    }

    @Test
    void 비우면_다음_조회에서_다시_받는다() {
        FakeCatalogClient client = new FakeCatalogClient();
        CatalogReader reader = reader(client);
        reader.findOption(PRODUCT_ID, OPTION_ID);

        client.optionStatus = "PAUSED";
        reader.evict(PRODUCT_ID);

        assertThat(reader.findOption(PRODUCT_ID, OPTION_ID)).get()
                .extracting(OptionSnapshot::isOnSale).isEqualTo(false);
        assertThat(client.calls.get()).isEqualTo(2);
    }

    @Test
    void 판매_상태와_판매_방식을_판정한다() {
        CatalogReader reader = reader(new FakeCatalogClient());

        OptionSnapshot snapshot = reader.findOption(PRODUCT_ID, OPTION_ID).orElseThrow();

        assertThat(snapshot.isPreorderProduct()).isTrue();
        assertThat(snapshot.isOnSale()).isTrue();
    }

    @Test
    void 요청한_사용자의_보안_맥락에서_부른다() {
        FakeCatalogClient client = new FakeCatalogClient();
        signIn(TOKEN);

        reader(client).findOption(PRODUCT_ID, OPTION_ID);

        assertThat(client.tokens).containsExactly(TOKEN);
    }

    @Test
    void catalog_가_토큰을_받지_않으면_401_로_알린다() {
        FakeCatalogClient client = new FakeCatalogClient();
        client.errorStatus = HttpStatus.UNAUTHORIZED;

        assertThatThrownBy(() -> reader(client).findOption(PRODUCT_ID, OPTION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(CommonErrorCode.UNAUTHENTICATED);
    }

    @Test
    void 일분이_지나면_가진_값을_돌려주고_그_요청의_토큰으로_뒤에서_한_번_다시_받는다() {
        FakeCatalogClient client = new FakeCatalogClient();
        CatalogReader reader = reader(client);
        signIn(TOKEN);
        reader.findOption(PRODUCT_ID, OPTION_ID);
        client.optionStatus = "PAUSED";
        clock.advance(CatalogReader.REFRESH_AFTER);

        signIn("later-token");
        assertThat(reader.findOption(PRODUCT_ID, OPTION_ID)).get()
                .as("다시 받기 전에는 가진 값").extracting(OptionSnapshot::isOnSale).isEqualTo(true);
        signIn("third-token");
        reader.findOption(PRODUCT_ID, OPTION_ID);
        assertThat(refreshes).as("다시 받는 중이면 또 받지 않는다").hasSize(1);

        SecurityContextHolder.clearContext();
        refreshes.poll().run();

        assertThat(client.tokens).as("맡긴 요청의 맥락을 옮겨 가 다른 스레드에서도 그 토큰으로 부른다")
                .containsExactly(TOKEN, "later-token");
        assertThat(reader.findOption(PRODUCT_ID, OPTION_ID)).get()
                .extracting(OptionSnapshot::isOnSale).isEqualTo(false);
    }

    @Test
    void 다시_받기가_실패하면_가진_값을_유지한다() {
        FakeCatalogClient client = new FakeCatalogClient();
        CatalogReader reader = reader(client);
        reader.findOption(PRODUCT_ID, OPTION_ID);
        clock.advance(CatalogReader.REFRESH_AFTER);
        reader.findOption(PRODUCT_ID, OPTION_ID);
        client.errorStatus = HttpStatus.UNAUTHORIZED;

        refreshes.poll().run();

        assertThat(reader.findOption(PRODUCT_ID, OPTION_ID)).isPresent();
        assertThat(client.calls.get()).isEqualTo(2);
    }

    @Test
    void 다시_받는_사이_비운_상품에는_옛_값을_다시_넣지_않는다() {
        FakeCatalogClient client = new FakeCatalogClient();
        CatalogReader reader = reader(client);
        reader.findOption(PRODUCT_ID, OPTION_ID);
        clock.advance(CatalogReader.REFRESH_AFTER);
        reader.findOption(PRODUCT_ID, OPTION_ID);

        reader.evict(PRODUCT_ID);
        refreshes.poll().run();

        assertThat(reader.cache().getIfPresent(PRODUCT_ID)).isNull();
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    /** 요청마다 보안 맥락이 새로 생기는 것처럼 새 맥락을 둔다. */
    private void signIn(String token) {
        SecurityContextHolder.setContext(new SecurityContextImpl(
                new NovaAuthentication(new AuthenticatedPrincipal("00000000-0000-7000-8000-000000000101", Role.USER),
                        token)));
    }

    private static String currentToken() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof NovaAuthentication nova
                ? nova.getCredentials() : null;
    }

    private CatalogReader reader(FakeCatalogClient client) {
        return new CatalogReader(client, DependencyGuards.passThrough(), refreshes::add, clock);
    }

    /** 시험이 시각을 옮기는 시계. */
    static class MovableClock extends Clock {

        private volatile Instant now = Instant.parse("2026-10-04T01:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static class FakeCatalogClient implements CatalogClient {

        final AtomicInteger calls = new AtomicInteger();
        /** 부른 순간 보안 맥락에 있던 토큰. 실제 호출은 이 맥락을 토큰 릴레이 인터셉터가 싣는다. */
        final List<String> tokens = new CopyOnWriteArrayList<>();
        volatile long delayMillis;
        volatile boolean notFound;
        volatile boolean unavailable;
        volatile boolean unreadable;
        volatile HttpStatus errorStatus;
        volatile String optionStatus = "ACTIVE";

        @Override
        public ApiResponse<ProductCatalog> getProduct(UUID productId) {
            calls.incrementAndGet();
            tokens.add(currentToken());
            if (unavailable) {
                throw new ResourceAccessException("connection refused");
            }
            if (unreadable) {
                throw new RestClientException("본문 변환 실패",
                        new HttpMessageNotReadableException("계약과 다른 본문", (HttpInputMessage) null));
            }
            if (errorStatus != null) {
                throw errorStatus.is4xxClientError()
                        ? HttpClientErrorException.create(errorStatus, errorStatus.getReasonPhrase(), null, null, null)
                        : HttpServerErrorException.create(errorStatus, errorStatus.getReasonPhrase(), null, null, null);
            }
            if (notFound) {
                throw HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null);
            }
            sleep();
            return CatalogStubs.preorderProduct(productId, CatalogStubs.option(OPTION_ID, optionStatus));
        }

        private void sleep() {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
