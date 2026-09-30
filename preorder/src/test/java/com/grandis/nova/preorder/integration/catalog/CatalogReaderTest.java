package com.grandis.nova.preorder.integration.catalog;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.preorder.support.CatalogStubs;
import com.grandis.nova.preorder.support.Concurrently.Outcome;
import com.grandis.nova.preorder.support.Concurrently;
import com.grandis.nova.preorder.support.DependencyGuards;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogReaderTest {

    static final Long PRODUCT_ID = 7L;
    static final Long OPTION_ID = 70L;

    @Test
    void 같은_상품은_한_번만_부르고_이후는_캐시에서_읽는다() {
        FakeCatalogClient client = new FakeCatalogClient();
        CatalogReader reader = new CatalogReader(client, DependencyGuards.passThrough());

        assertThat(reader.findOption(PRODUCT_ID, OPTION_ID)).get()
                .extracting(OptionSnapshot::optionTitle).isEqualTo("블랙 / 256GB");
        assertThat(reader.findOption(PRODUCT_ID, OPTION_ID)).isPresent();

        assertThat(client.calls.get()).isEqualTo(1);
    }

    @Test
    void 오픈_순간_동시에_몰려도_catalog_는_한_번만_부른다() throws Exception {
        FakeCatalogClient client = new FakeCatalogClient();
        client.delayMillis = 200;
        CatalogReader reader = new CatalogReader(client, DependencyGuards.passThrough());
        int requests = 20;

        List<Outcome<Boolean>> outcomes = Concurrently.run(requests, i -> () ->
                reader.findOption(PRODUCT_ID, OPTION_ID).isPresent());

        assertThat(outcomes).allMatch(o -> o.succeeded() && o.value());

        assertThat(client.calls.get()).isEqualTo(1);
    }

    @Test
    void 그_상품의_옵션이_아니면_비어_있다() {
        CatalogReader reader = new CatalogReader(new FakeCatalogClient(), DependencyGuards.passThrough());

        assertThat(reader.findOption(PRODUCT_ID, 999L)).isEmpty();
    }

    @Test
    void 없는_상품이면_비어_있다() {
        FakeCatalogClient client = new FakeCatalogClient();
        client.notFound = true;

        assertThat(new CatalogReader(client, DependencyGuards.passThrough()).findOption(PRODUCT_ID, OPTION_ID)).isEmpty();
    }

    @Test
    void 캐시에_없는데_catalog_가_응답하지_않으면_503_으로_알린다() {
        FakeCatalogClient client = new FakeCatalogClient();
        client.unavailable = true;

        assertThatThrownBy(() -> new CatalogReader(client, DependencyGuards.passThrough()).findOption(PRODUCT_ID, OPTION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
    }

    @Test
    void 캐시에_없는데_catalog_가_5xx_면_503_으로_알린다() {
        FakeCatalogClient client = new FakeCatalogClient();
        client.errorStatus = HttpStatus.SERVICE_UNAVAILABLE;

        assertThatThrownBy(() -> new CatalogReader(client, DependencyGuards.passThrough()).findOption(PRODUCT_ID, OPTION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
    }

    @Test
    void 없는_상품_외의_4xx_는_재시도_안내가_아니라_연동_오류다() {
        FakeCatalogClient client = new FakeCatalogClient();
        client.errorStatus = HttpStatus.BAD_REQUEST;
        CatalogReader reader = new CatalogReader(client, DependencyGuards.passThrough());

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

        assertThatThrownBy(() -> new CatalogReader(client, DependencyGuards.passThrough()).findOption(PRODUCT_ID, OPTION_ID))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(BusinessException.class);
    }

    @Test
    void 비우면_다음_조회에서_다시_받는다() {
        FakeCatalogClient client = new FakeCatalogClient();
        CatalogReader reader = new CatalogReader(client, DependencyGuards.passThrough());
        reader.findOption(PRODUCT_ID, OPTION_ID);

        client.optionStatus = "PAUSED";
        reader.evict(PRODUCT_ID);

        assertThat(reader.findOption(PRODUCT_ID, OPTION_ID)).get()
                .extracting(OptionSnapshot::isOnSale).isEqualTo(false);
        assertThat(client.calls.get()).isEqualTo(2);
    }

    @Test
    void 판매_상태와_판매_방식을_판정한다() {
        CatalogReader reader = new CatalogReader(new FakeCatalogClient(), DependencyGuards.passThrough());

        OptionSnapshot snapshot = reader.findOption(PRODUCT_ID, OPTION_ID).orElseThrow();

        assertThat(snapshot.isPreorderProduct()).isTrue();
        assertThat(snapshot.isOnSale()).isTrue();
    }

    static class FakeCatalogClient implements CatalogClient {

        final AtomicInteger calls = new AtomicInteger();
        volatile long delayMillis;
        volatile boolean notFound;
        volatile boolean unavailable;
        volatile boolean unreadable;
        volatile HttpStatus errorStatus;
        volatile String optionStatus = "ACTIVE";

        @Override
        public ApiResponse<ProductCatalog> getProduct(Long productId) {
            calls.incrementAndGet();
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
