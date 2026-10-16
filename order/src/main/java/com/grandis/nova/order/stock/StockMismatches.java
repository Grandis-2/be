package com.grandis.nova.order.stock;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 재고를 옮기다 장부가 어긋난 것을 알아챈 횟수(order.stock.mismatch, 자리별). 각 자리의 "재고 어긋남" ERROR 와 함께 센다 — 경보는 이 증가로 건다.
 * 자리마다 처음부터 0 으로 등록한다 — 첫 증가 전에 시계열이 없으면 increase() 가 그 첫 번을 놓친다.
 * 롤백되는 트랜잭션 안에서 세도 되돌리지 않는다 — 어긋남을 본 사실은 그대로다. 그래서 사건 수가 아니라 알아챈 횟수다 — 롤백된 일이
 * 다시 오면(만료 주기 · 메시지 재수신) 고칠 때까지 또 센다.
 */
@Component
public class StockMismatches {

    static final String METER = "order.stock.mismatch";

    /**
     * 어긋남을 알아채는 자리. 태그 값은 이름을 바꾸면 경보 · 대시보드가 끊기므로 고정한다 — 바로 구매가 생겨 결제 · 미결제 취소가 장바구니만의
     * 일이 아니게 됐어도 cart-payment 는 그대로 둔다.
     */
    public enum Site {
        /** 장바구니 · 바로 구매 주문 결제 승인의 판매 확정(확보 → 판매)에서 확보가 모자람. 결제는 반영한다. */
        CART_PAYMENT("cart-payment"),
        /** 결제 안 된 장바구니 · 바로 구매 주문 취소(사용자 · 만료)의 반환에서 확보가 모자람. 취소를 롤백한다. */
        UNPAID_CANCEL("unpaid-cancel"),
        /** 새 장바구니 주문이 앞 주문을 바꿀 때 반환할 확보가 모자람. 새 주문을 만들지 않는다. */
        CART_REPLACE("cart-replace"),
        /** 드로우 결과 반영의 당첨분 판매 확정에서 확보가 모자람. 당첨 주문은 만든다. */
        DRAW_SALE("draw-sale"),
        /** 드로우 결과 반영의 남은 확보 반납에서 확보가 모자람. 반영을 롤백한다. */
        DRAW_RELEASE("draw-release");

        private final String tag;

        Site(String tag) {
            this.tag = tag;
        }
    }

    private final Map<Site, Counter> counters = new EnumMap<>(Site.class);

    public StockMismatches(MeterRegistry registry) {
        for (Site site : Site.values()) {
            counters.put(site, Counter.builder(METER).tag("site", site.tag)
                    .description("재고를 옮기다 장부 어긋남을 알아챈 횟수").register(registry));
        }
    }

    public void record(Site site) {
        counters.get(site).increment();
    }
}
