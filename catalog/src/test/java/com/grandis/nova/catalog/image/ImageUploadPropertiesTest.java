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
            .withPropertyValues("nova.upload.region=ap-northeast-2");

    @Test
    @DisplayName("대조군 — 버킷 · https CDN 주소(끝 / 있어도 됨)면 뜨고, 이미지 주소 접두는 CDN 주소 + /")
    void startsWithBucketAndCdn() {
        runner.withPropertyValues("nova.upload.bucket=b", "nova.upload.cdn-base-url=https://cdn.example.com/").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ImageUploadProperties.class).imageUrlPrefix()).isEqualTo("https://cdn.example.com/");
        });
    }

    @Test
    @DisplayName("버킷이 없거나 · 예시 자리표시(CHANGE_ME)거나, CDN 주소가 없거나 · http 거나 · 사용자 정보 · 경로 · 쿼리가 있으면 기동 실패")
    void refusesMissingOrMalformed() {
        runner.withPropertyValues("nova.upload.cdn-base-url=https://cdn.example.com")
                .run(context -> assertThat(context).getFailure().rootCause().hasMessageContaining("nova.upload.bucket"));
        runner.withPropertyValues("nova.upload.bucket=CHANGE_ME", "nova.upload.cdn-base-url=https://cdn.example.com")
                .run(context -> assertThat(context).getFailure().rootCause().hasMessageContaining("placeholder"));
        for (String cdn : new String[]{null, "http://cdn.example.com", "https://cdn.example.com/images", "https://cdn.example.com?x=1", "https://u:p@cdn.example.com"}) {
            ApplicationContextRunner withBucket = runner.withPropertyValues("nova.upload.bucket=b");
            (cdn == null ? withBucket : withBucket.withPropertyValues("nova.upload.cdn-base-url=" + cdn))
                    .run(context -> assertThat(context).as(String.valueOf(cdn)).getFailure().rootCause()
                            .hasMessageContaining("nova.upload.cdn-base-url"));
        }
    }
}
