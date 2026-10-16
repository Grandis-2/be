package com.grandis.nova.catalog.image;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 사진 주소 · 객체 키 · id 사이의 변환. 주소 → 키는 URI 경로로 구한다(앞 "/" 를 뗀 것) — CDN 접두를 떼는 방식이면 CDN 도메인이 바뀐 뒤
 * 옛 주소의 키가 안 맞아 조용히 USED 로 남는다. 정리 배치도 같은 규칙(주소가 "/" + 키로 끝나는지)으로 드로우 스냅샷을 비교한다.
 * 키 → id 는 키 안의 UUID 다({prefix}/{uuid}.{ext}).
 */
public final class ImageKeys {

    private static final Pattern KEY = Pattern.compile("([a-z]+)/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.([a-z]+)");

    private ImageKeys() {
    }

    public static String key(ImagePurpose purpose, UUID id, ImageFormat format) {
        return purpose.keyPrefix() + id + "." + format.extension();
    }

    /** 주소의 객체 키. 주소로 읽히지 않거나 경로가 없으면 빈 값. */
    public static Optional<String> keyOf(String url) {
        try {
            String path = URI.create(url).getRawPath();
            if (path == null || path.length() <= 1 || path.charAt(0) != '/') {
                return Optional.empty();
            }
            return Optional.of(path.substring(1));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** 서버가 만든 모양({prefix}/{uuid}.{ext})의 키에 든 id. 모양이 아니면 빈 값. */
    public static Optional<UUID> idOf(String key) {
        Matcher m = KEY.matcher(key);
        return m.matches() ? Optional.of(UUID.fromString(m.group(2))) : Optional.empty();
    }
}
