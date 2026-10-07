package com.grandis.nova.catalog.web;

/**
 * DB 제약 위반이 어느 제약에서 났는지 — 드라이버 메시지에 제약 이름이 들어 있다(MySQL 1062 "… for key 'product_options.uq_option_sku'").
 * 제약 이름으로 가르지 않고 한 catch 로 받으면 다른 제약(길이 1406 · 다른 UNIQUE)이 엉뚱한 칸의 400 이 된다.
 */
public final class ConstraintViolations {

    private ConstraintViolations() {
    }

    /** 예외 사슬 어딘가의 메시지가 이 문자열을 담고 있는가 — 접두어로도 쓴다(이름 여럿을 한 번에). */
    public static boolean mentions(Throwable e, String constraint) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(constraint)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 이 이름의 키 하나만 — MySQL 1062 는 "for key '표.키이름'" 으로 적으므로 앞의 점과 뒤의 따옴표까지 맞춘다. 그냥 포함 여부로 보면
     * uq_option_sku 가 uq_option_sku_old 같은 이름에도 걸린다.
     */
    public static boolean mentionsKey(Throwable e, String key) {
        return mentions(e, "." + key + "'");
    }
}
