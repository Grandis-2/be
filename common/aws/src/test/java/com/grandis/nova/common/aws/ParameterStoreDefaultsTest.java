package com.grandis.nova.common.aws;

import io.floci.testcontainers.FlociContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.ParameterType;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/** 앱 안의 SsmClient 빈은 기본으로 꺼져도, 기동할 때의 Parameter Store 가져오기는 값을 읽는다. */
class ParameterStoreDefaultsTest {

    static final String REGION = "ap-northeast-2";

    static FlociContainer floci;

    @BeforeAll
    static void startFloci() {
        floci = new FlociContainer("floci/floci:2.1.0");
        floci.start();
    }

    @AfterAll
    static void stopFloci() {
        floci.stop();
    }

    @Test
    void 기본으로는_앱_안_SsmClient_빈을_만들지_않는다() {
        try (ConfigurableApplicationContext context = run()) {
            assertThat(context.getBeanNamesForType(SsmClient.class)).isEmpty();
            assertThat(context.getEnvironment().getProperty(ParameterStoreDefaults.CLIENT_ENABLED)).isEqualTo("false");
        }
    }

    @Test
    void 설정이_켜면_그_값이_이긴다() {
        try (ConfigurableApplicationContext context = run("--spring.cloud.aws.parameterstore.enabled=true",
                "--spring.cloud.aws.region.static=" + REGION)) {
            assertThat(context.getBeanNamesForType(SsmClient.class)).hasSize(1);
        }
    }

    @Test
    void 빈을_꺼도_기동할_때_Parameter_Store_값을_가져온다() {
        try (SsmClient ssm = SsmClient.builder()
                .region(Region.of(REGION))
                .endpointOverride(URI.create(floci.getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(floci.getAccessKey(), floci.getSecretKey())))
                .build()) {
            ssm.putParameter(request -> request.name("/nova/test/common/nova.sample").value("from-ssm")
                    .type(ParameterType.STRING).overwrite(true));
        }

        try (ConfigurableApplicationContext context = run(
                "--spring.config.import=aws-parameterstore:/nova/test/common/",
                "--spring.cloud.aws.parameterstore.endpoint=" + floci.getEndpoint(),
                "--spring.cloud.aws.region.static=" + REGION,
                "--spring.cloud.aws.credentials.access-key=" + floci.getAccessKey(),
                "--spring.cloud.aws.credentials.secret-key=" + floci.getSecretKey())) {
            assertThat(context.getEnvironment().getProperty("nova.sample")).isEqualTo("from-ssm");
            assertThat(context.getEnvironment().getProperty(ParameterStoreDefaults.CLIENT_ENABLED)).isEqualTo("false");
        }
    }

    private ConfigurableApplicationContext run(String... args) {
        SpringApplication application = new SpringApplication(TestApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        return application.run(args);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TestApplication {
    }
}
