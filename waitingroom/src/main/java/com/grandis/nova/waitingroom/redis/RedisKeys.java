package com.grandis.nova.waitingroom.redis;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Redis 키를 한 곳에서만 만든다. 모델별 키는 해시 태그 {productKey} 로 한 슬롯에 묶는다 — Lua 가 KEYS 로
 * 함께 만지는 키가 슬롯이 갈리면 클러스터가 거절한다. 모델 키는 상품 ID 의 UUID 소문자 표준 표기만 받는다 —
 * 같은 상품이 대소문자만 다른 두 키(두 줄)로 갈리지 않게, 입장권을 검증하는 preorder 의 표기({@code UUID.toString()})와 맞게.
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
    /** 일정 전체 재발행을 요청한 표식. 있으면 다시 요청하지 않는다(리더가 바뀌어도). */
    public static final String RESYNC_REQUESTED = "wr:resync-requested";
    /** 일정을 모른 채 연달아 요청한 횟수. 늘수록 다음 요청까지 오래 기다린다. */
    public static final String RESYNC_ATTEMPTS = "wr:resync-attempts";

    private static final Pattern PRODUCT_KEY = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

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

    /** admission_forget.lua 의 KEYS 순서. */
    public static List<String> forget(String productKey) {
        return List.of(grace(productKey));
    }

    /** queue_status.lua 의 KEYS 순서. */
    public static List<String> status(String productKey) {
        return List.of(queue(productKey), admitted(productKey), alive(productKey), grace(productKey));
    }

    public static List<String> apply(String productKey) {
        return List.of(queue(productKey), admitted(productKey), applyFence(productKey), tagged("applyround", productKey));
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

    /** 모양이 틀린 모델 키면 던진다 — 해시 태그가 깨진 키나 이상한 필드를 쓰지 않게. */
    public static String requireProductKey(String productKey) {
        return checked(productKey);
    }

    private static String checked(String productKey) {
        if (!validProductKey(productKey)) {
            throw new IllegalArgumentException("모델 키는 UUID 소문자 표준 표기여야 한다: " + productKey);
        }
        return productKey;
    }
}
