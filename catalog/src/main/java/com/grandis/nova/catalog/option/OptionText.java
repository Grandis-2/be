package com.grandis.nova.catalog.option;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 옵션 축 키 · 값의 저장 규칙. 등록 · 수정 · 목록 필터가 같은 규칙을 쓴다 — 저장과 비교가 갈리면 필터가 빗나간다.
 * color · storage 두 축만 목록 필터 키다(명세의 키 제한).
 */
public final class OptionText {

    public static final String COLOR = "color";
    public static final String STORAGE = "storage";

    /** 공백 연속 — 목록 필터가 요청마다 부른다(String.replaceAll 은 부를 때마다 컴파일한다) */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private OptionText() {
    }

    /** 목록 필터에 쓰는 축인가(color · storage). */
    public static boolean isFilterAxis(String axisKey) {
        return COLOR.equals(axisKey) || STORAGE.equals(axisKey);
    }

    /** 축 키에 맞는 정규화. 용량은 공백을 지우고 대문자로(256 gb → 256GB), 나머지는 {@link #normalize}. */
    public static String normalizeFor(String axisKey, String text) {
        String normalized = normalize(text);
        if (STORAGE.equals(axisKey)) {
            return normalized.replace(" ", "").toUpperCase(Locale.ROOT);
        }
        return normalized;
    }

    /** NFC · 트림 · 연속 공백을 하나로. 비면 거절한다. */
    public static String normalize(String text) {
        if (text == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        String normalized = WHITESPACE.matcher(Normalizer.normalize(text, Normalizer.Form.NFC).strip()).replaceAll(" ");
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("value must not be blank");
        }
        return normalized;
    }
}
