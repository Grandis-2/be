package com.grandis.nova.preorder.config;

import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ALB 는 경로 접두어로 서비스를 고른다. preorder 의 엔드포인트가 모두 preorder 접두어 아래에 있어야 규칙 하나로 닿는다 —
 * 다른 서비스 도메인(/api/v1/products 등) 밑에 두면 그 서비스로 가거나 규칙을 경로마다 따로 걸어야 한다.
 */
@PreorderIntegrationTest
@AutoConfigureMockMvc
class ApiPathPrefixTest {

    static final List<String> PREFIXES = List.of("/api/v1/preorders", "/api/v1/admin/preorders", "/internal/preorders");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    void preorder_엔드포인트는_모두_preorder_접두어_아래에_있다() {
        List<String> paths = mappings.getHandlerMethods().entrySet().stream()
                .filter(entry -> entry.getValue().getBeanType().getPackageName().startsWith("com.grandis.nova.preorder"))
                .flatMap(entry -> entry.getKey().getPathPatternsCondition().getPatternValues().stream())
                .toList();

        assertThat(paths).isNotEmpty();
        assertThat(paths).allSatisfy(path -> assertThat(PREFIXES).anySatisfy(prefix ->
                assertThat(path.equals(prefix) || path.startsWith(prefix + "/")).as(path).isTrue()));
    }
}
