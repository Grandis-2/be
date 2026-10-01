package com.grandis.nova.waitingroom.redis;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Redis 키를 한 곳에서만 만든다. 모델별 키는 해시 태그 {productKey} 로 한 슬롯에 묶는다 — Lua 가 KEYS 로
 * 함께 만지는 키가 슬롯이 갈리면 클러스터가 거절한다. 모델 키는 상품 ID(양의 정수)만 받는다.
 */
public final class RedisKeys {

    /** 리더가 발행하는 판정 재료. */
    public static final String SNAPSHOT = "wr:snapshot";
    /** 판정 재료를 마지막으로 쓴 임기. 태그 안 글자가 SNAPSHOT 과 같아 같은 슬롯이다. */
    public static final String SNAPSHOT_FENCE = "{wr:snapshot}:fence";
    public static final String LEADER = "wr:leader";
    public static final String LEADER_GENERATION = "{wr:leader}:gen";
    /** 살아 있는 게이트웨이와 한산 통과 수. */
    public static final String GATEWAYS = "wr:gateways";
    /** 배분 대상 모델과 접수 일정(회차 일정 이벤트가 쓴다). */
    public static final String PRODUCTS = "wr:products";
    /** 운영값(초당 입장 인원 · 모델별 상한 · 최대 대기 시간). 관리자 API 가 쓴다. */
    public static final String SETTINGS = "wr:settings";

    private static final Pattern PRODUCT_KEY = Pattern.compile("[1-9][0-9]{0,18}");

    private RedisKeys() {
    }

    /** 요청에서 온 값이 키에 들어가므로 모양을 먼저 본다. 해시 태그를 깨는 글자가 들어가면 슬롯이 갈린다. */
    public static boolean validProductKey(String productKey) {
        return productKey != null && PRODUCT_KEY.matcher(productKey).matches();
    }

    /** enqueue.lua 의 KEYS 순서. */
    public static List<String> enqueue(String productKey) {
        return List.of(queue(productKey), maxScore(productKey), alive(productKey), admitted(productKey), grace(productKey));
    }

    /** queue_status.lua 의 KEYS 순서. */
    public static List<String> status(String productKey) {
        return List.of(queue(productKey), admitted(productKey), alive(productKey), grace(productKey));
    }

    public static List<String> apply(String productKey) {
        return List.of(queue(productKey), admitted(productKey), applyFence(productKey));
    }

    public static List<String> depth(String productKey) {
        return List.of(queue(productKey), admitted(productKey));
    }

    public static List<String> sweep(String productKey) {
        return List.of(queue(productKey), grace(productKey), alive(productKey), admitted(productKey), applyFence(productKey));
    }

    public static List<String> close(String productKey) {
        return List.of(queue(productKey), alive(productKey), tagged("closefence", productKey), grace(productKey));
    }

    /** 모델별 상한 운영값의 필드 이름. */
    public static String capField(String productKey) {
        return "cap:" + checked(productKey);
    }

    static String queue(String productKey) {
        return tagged("queue", productKey);
    }

    static String admitted(String productKey) {
        return tagged("admitted", productKey);
    }

    private static String maxScore(String productKey) {
        return tagged("maxscore", productKey);
    }

    private static String alive(String productKey) {
        return tagged("alive", productKey);
    }

    private static String grace(String productKey) {
        return tagged("grace", productKey);
    }

    private static String applyFence(String productKey) {
        return tagged("applyfence", productKey);
    }

    private static String tagged(String name, String productKey) {
        return "wr:" + name + ":{" + checked(productKey) + "}";
    }

    private static String checked(String productKey) {
        if (!validProductKey(productKey)) {
            throw new IllegalArgumentException("모델 키는 양의 정수여야 한다: " + productKey);
        }
        return productKey;
    }
}
