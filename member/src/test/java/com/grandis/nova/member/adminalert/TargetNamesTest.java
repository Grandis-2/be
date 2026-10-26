package com.grandis.nova.member.adminalert;

import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.member.adminalert.AdminAlertRules.TargetKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("대상 이름 — 공개 조회. 없음(404)과 실패를 가르고, 예외를 던지지 않는다")
class TargetNamesTest {

    final TargetClients.CatalogProductClient products = mock(TargetClients.CatalogProductClient.class);
    final TargetClients.DrawClient draws = mock(TargetClients.DrawClient.class);
    final TargetNames names = new TargetNames(products, draws);

    @Test
    @DisplayName("상품은 catalog, 회차는 order 에서 title 을 받는다. 200자를 넘으면 자른다")
    void found() {
        UUID product = UUID.randomUUID();
        UUID draw = UUID.randomUUID();
        when(products.product(product)).thenReturn(ok("아이폰 18 Pro"));
        when(draws.draw(draw)).thenReturn(ok("가".repeat(250)));

        assertThat(names.find(TargetKind.PRODUCT, product.toString())).isEqualTo(new TargetNames.Lookup("아이폰 18 Pro", false));
        assertThat(names.find(TargetKind.DRAW, draw.toString()).name()).hasSize(200);
    }

    @Test
    @DisplayName("404(비공개 · 미완료)는 실패가 아닌 없음, 5xx · 연결 실패는 실패다")
    void absentAndFailed() {
        when(products.product(any())).thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "nf", null, null, null));
        assertThat(names.find(TargetKind.PRODUCT, UUID.randomUUID().toString())).isEqualTo(TargetNames.Lookup.ABSENT);

        when(draws.draw(any())).thenThrow(HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "down", null, null, null));
        assertThat(names.find(TargetKind.DRAW, UUID.randomUUID().toString())).isEqualTo(TargetNames.Lookup.FAILED);

        doThrow(new ResourceAccessException("timeout")).when(draws).draw(any());
        assertThat(names.find(TargetKind.DRAW, UUID.randomUUID().toString())).isEqualTo(TargetNames.Lookup.FAILED);
    }

    @Test
    @DisplayName("대상이 상품 · 회차가 아니거나 id 가 UUID 가 아니면 묻지 않는다")
    void notAsked() {
        assertThat(names.find(TargetKind.SERVICE, "order")).isEqualTo(TargetNames.Lookup.ABSENT);
        assertThat(names.find(TargetKind.PRODUCT, "p-iphone")).isEqualTo(TargetNames.Lookup.ABSENT);
        verifyNoInteractions(products, draws);
    }

    private static ApiResponse<TargetClients.Titled> ok(String title) {
        return ApiResponse.ok(new TargetClients.Titled(title));
    }
}
