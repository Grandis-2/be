package com.grandis.nova.probe.ddl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.grandis.nova.member.support.Containers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * "ddl-auto: validate 가 customers 엔티티와 DDL 불일치를 잡는가 — 일부러 칸 하나 빼고 기동". 없는 칸을 가진 엔티티로 띄워 본다.
 * 실제 MySQL(컨테이너).
 */
@DisplayName("ddl-auto=validate 실측")
class DdlValidateProbeTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = BrokenCustomer.class)
    static class ProbeApp {
    }

    @Test
    @DisplayName("DDL 에 없는 칸을 가진 엔티티는 기동이 실패하고, 원인 메시지에 그 칸 이름이 나온다")
    void missingColumnFailsStartup() {
        Throwable t = catchThrowable(() -> new SpringApplicationBuilder(ProbeApp.class)
                .web(WebApplicationType.NONE)
                .properties(Containers.datasourceProperties())
                .properties(
                        "spring.jpa.hibernate.ddl-auto=validate",
                        "spring.flyway.enabled=false",
                        "spring.main.banner-mode=off")
                .run()
                .close());

        assertThat(t).isNotNull();
        Throwable root = t;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertThat(root.getClass().getName()).isEqualTo("org.hibernate.tool.schema.spi.SchemaManagementException");
        assertThat(root.getMessage()).contains("missing column [column_that_does_not_exist]").contains("customers");
    }
}
