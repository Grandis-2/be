package com.grandis.nova.catalog.support;

import com.grandis.nova.catalog.image.ImageAttachments;
import com.grandis.nova.catalog.image.ImagePurpose;
import com.grandis.nova.catalog.image.ImageUploadProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.util.Collection;
import java.util.function.Function;

/**
 * 공용 시험 컨텍스트의 사진 붙이기 · 떼기는 아무것도 하지 않는다 — 기존 시험 본문의 사진 주소(https://img/b0.jpg 같은)는 업로드로 만든 행이 없다.
 * 붙이기 규칙 · 경로마다의 상태 전이는 진짜 구현을 켠 ImageAttachmentApiTest 가 실제 행으로 본다({@link #REAL} = true).
 */
@TestConfiguration(proxyBeanMethods = false)
public class ImageAttachmentsTestConfig {

    /** true 면 이 대역을 만들지 않고 진짜 구현이 돈다. */
    public static final String REAL = "catalog.test.real-image-attachments";

    @Bean
    @Primary
    @ConditionalOnProperty(name = REAL, havingValue = "false", matchIfMissing = true)
    ImageAttachments noOpImageAttachments(ImageUploadProperties properties, Clock clock) {
        return new ImageAttachments(null, properties, clock) {
            @Override
            public void sync(ImagePurpose purpose, Collection<String> before, Collection<String> after, Function<String, String> fieldOf) {
            }
        };
    }
}
