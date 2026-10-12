package com.grandis.nova.catalog.image;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** 설정이 빠지거나 틀리면 기동하지 않는다 — 업로드가 500 이 되거나 등록의 사진 주소 검사가 열린 채로 뜨지 않게. */
@DisplayName("이미지 업로드 설정 — 버킷 · CDN 주소가 없거나 틀리면 기동 실패")
class ImageUploadPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(UserConfigurations.of(ImageUploadConfig.class))
            .withPropertyValues("nova.images.region=ap-northeast-2");

    @Test
    @DisplayName("대조군 — 버킷 · https CDN 주소(끝 / 있어도 됨)면 뜨고, 이미지 주소 접두는 CDN 주소 + /")
    void startsWithBucketAndCdn() {
        runner.withPropertyValues("nova.images.bucket=b", "nova.images.cdn-base-url=https://cdn.example.com/").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ImageUploadProperties.class).imageUrlPrefix()).isEqualTo("https://cdn.example.com/");
        });
    }

    @Test
    @DisplayName("버킷이 없거나 · 예시 자리표시(CHANGE_ME)거나, CDN 주소가 없거나 · http 거나 · 경로 · 쿼리가 있으면 기동 실패")
    void refusesMissingOrMalformed() {
        runner.withPropertyValues("nova.images.cdn-base-url=https://cdn.example.com")
                .run(context -> assertThat(context).getFailure().rootCause().hasMessageContaining("nova.images.bucket"));
        runner.withPropertyValues("nova.images.bucket=CHANGE_ME", "nova.images.cdn-base-url=https://cdn.example.com")
                .run(context -> assertThat(context).getFailure().rootCause().hasMessageContaining("placeholder"));
        for (String cdn : new String[]{null, "http://cdn.example.com", "https://cdn.example.com/images", "https://cdn.example.com?x=1"}) {
            ApplicationContextRunner withBucket = runner.withPropertyValues("nova.images.bucket=b");
            (cdn == null ? withBucket : withBucket.withPropertyValues("nova.images.cdn-base-url=" + cdn))
                    .run(context -> assertThat(context).as(String.valueOf(cdn)).getFailure().rootCause()
                            .hasMessageContaining("nova.images.cdn-base-url"));
        }
    }
}
