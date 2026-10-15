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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * 공용 시험 컨텍스트의 사진 붙이기 · 떼기는 부른 기록만 남긴다 — 기존 시험 본문의 사진 주소(https://img/b0.jpg 같은)는 업로드로 만든 행이 없다.
 * 붙이기 규칙 자체는 진짜 구현을 켠 ImageAttachmentApiTest 가 경로마다 실제 행으로 본다({@link #REAL} = true).
 */
@TestConfiguration(proxyBeanMethods = false)
public class ImageAttachmentsTestConfig {

    /** true 면 이 가짜를 만들지 않고 진짜 구현이 돈다. */
    public static final String REAL = "catalog.test.real-image-attachments";

    @Bean
    @Primary
    @ConditionalOnProperty(name = REAL, havingValue = "false", matchIfMissing = true)
    RecordingImageAttachments recordingImageAttachments(ImageUploadProperties properties, Clock clock) {
        return new RecordingImageAttachments(properties, clock);
    }

    /** 부른 그대로를 기록한다. */
    public static class RecordingImageAttachments extends ImageAttachments {

        public record Call(ImagePurpose purpose, List<String> before, List<String> after) {
        }

        private final List<Call> calls = new CopyOnWriteArrayList<>();

        RecordingImageAttachments(ImageUploadProperties properties, Clock clock) {
            super(null, properties, clock);
        }

        @Override
        public void sync(ImagePurpose purpose, Collection<String> before, Collection<String> after, Function<String, String> fieldOf) {
            calls.add(new Call(purpose, List.copyOf(before), List.copyOf(after)));
        }

        public List<Call> calls() {
            return calls;
        }
    }
}
