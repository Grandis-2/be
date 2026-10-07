package com.grandis.nova.preorder.integration;

import com.grandis.nova.common.security.AuthenticatedPrincipal;
import com.grandis.nova.common.security.NovaAuthentication;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import org.springframework.web.service.registry.HttpServiceGroup;
import org.springframework.web.service.registry.HttpServiceGroupConfigurer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** catalog · order 클라이언트 그룹에 토큰 릴레이가 붙어, 요청한 사용자의 토큰이 실제 요청에 실리는지. */
class TokenRelayConfigTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void catalog_와_order_그룹의_내부_호출에_요청한_사용자의_토큰을_싣는다() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://catalog");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RecordingGroups groups = new RecordingGroups(builder);
        RestClientHttpServiceGroupConfigurer configurer = new TokenRelayConfig().tokenRelay();

        configurer.configureGroups(groups);
        CatalogClient client = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(builder.build())).build()
                .createClient(CatalogClient.class);
        SecurityContextHolder.getContext().setAuthentication(
                new NovaAuthentication(new AuthenticatedPrincipal("00000000-0000-7000-8000-000000000101", Role.USER),
                        "member-token"));
        UUID productId = UUID.randomUUID();
        server.expect(requestTo("http://catalog/internal/products/" + productId + "/options"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer member-token"))
                .andRespond(withSuccess("{\"success\":true,\"data\":null}", MediaType.APPLICATION_JSON));

        client.getProduct(productId);

        assertThat(groups.names).containsExactlyInAnyOrder(Dependencies.CATALOG, Dependencies.ORDER);
        server.verify();
    }

    /** 고른 그룹 이름을 남기고, 클라이언트 설정은 받은 빌더 하나에 적용한다. */
    static class RecordingGroups implements HttpServiceGroupConfigurer.Groups<RestClient.Builder> {

        final List<String> names = new ArrayList<>();
        private final RestClient.Builder builder;

        RecordingGroups(RestClient.Builder builder) {
            this.builder = builder;
        }

        @Override
        public HttpServiceGroupConfigurer.Groups<RestClient.Builder> filterByName(String... groupNames) {
            names.addAll(List.of(groupNames));
            return this;
        }

        @Override
        public HttpServiceGroupConfigurer.Groups<RestClient.Builder> filter(Predicate<HttpServiceGroup> predicate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void forEachClient(HttpServiceGroupConfigurer.ClientCallback<RestClient.Builder> callback) {
            callback.withClient(null, builder);
        }

        @Override
        public void forEachClient(HttpServiceGroupConfigurer.InitializingClientCallback<RestClient.Builder> callback) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void forEachProxyFactory(HttpServiceGroupConfigurer.ProxyFactoryCallback callback) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void forEachGroup(HttpServiceGroupConfigurer.GroupCallback<RestClient.Builder> callback) {
            throw new UnsupportedOperationException();
        }
    }
}
