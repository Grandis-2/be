package com.grandis.nova.catalog.image;

import java.util.Arrays;
import java.util.Optional;

/** 받는 사진 형식. 앞 바이트(시그니처)로만 정한다 — Content-Type · 파일 이름은 믿지 않는다. */
public enum ImageFormat {

    JPEG("jpg", "image/jpeg", "jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
    PNG("png", "image/png", "png", new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});

    /** 판정에 읽는 앞 바이트 수(가장 긴 시그니처). */
    static final int SIGNATURE_LENGTH = 8;

    private final String extension;
    private final String contentType;
    private final String imageIoName;
    private final byte[] signature;

    ImageFormat(String extension, String contentType, String imageIoName, byte[] signature) {
        this.extension = extension;
        this.contentType = contentType;
        this.imageIoName = imageIoName;
        this.signature = signature;
    }

    /** 앞 바이트가 맞는 형식. 그 밖(WebP · GIF 포함)은 빈 값. */
    public static Optional<ImageFormat> sniff(byte[] bytes) {
        for (ImageFormat format : values()) {
            if (bytes.length >= format.signature.length
                    && Arrays.equals(bytes, 0, format.signature.length, format.signature, 0, format.signature.length)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }

    /** 객체 키 확장자. */
    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }

    /** ImageIO 리더 · 라이터 이름. */
    String imageIoName() {
        return imageIoName;
    }

    /** 확장자로 찾는다. 서버가 만든 키만 이 확장자를 쓴다. */
    public static Optional<ImageFormat> ofExtension(String extension) {
        return Arrays.stream(values()).filter(f -> f.extension.equals(extension)).findFirst();
    }
}
