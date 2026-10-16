package com.grandis.nova.catalog.image;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.ByteArrayInputStream;

/** 다시 인코딩한 사진을 업로드 버킷에 올린다. 시간 상한 · 재시도는 클라이언트({@link ImageUploadConfig})가 건다. */
@Component
class ImageStorage {

    private final S3Client s3;
    private final ImageUploadProperties properties;

    ImageStorage(S3Client s3, ImageUploadProperties properties) {
        this.s3 = s3;
        this.properties = properties;
    }

    /** 실패하면(거절 · 시간 초과) SDK 예외를 그대로 던진다. */
    void put(String key, ImageFormat format, byte[] bytes) {
        // 시도마다 새 스트림을 연다 — fromBytes 는 배열을 한 벌 더 복사하고, 스트림 하나를 넘기면 재시도 때 처음으로 되감지 못한다
        s3.putObject(put -> put.bucket(properties.bucket()).key(key).contentType(format.contentType()).contentLength((long) bytes.length),
                RequestBody.fromContentProvider(() -> new ByteArrayInputStream(bytes), bytes.length, format.contentType()));
    }
}
