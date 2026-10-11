package com.grandis.nova.order.cart.domain.repository;

import com.grandis.nova.order.cart.domain.model.CartLine;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 장바구니 읽기 · 쓰기 포트. */
public interface CartStore {

    /** 그 회원의 줄 전체, 만든 순서. 잠그지 않는다. */
    List<CartLine> findByCustomer(UUID customerId);

    /**
     * 그 회원의 줄 전체를 잠가 읽는다(FOR UPDATE) — 담기의 합산 · 줄 수 판정과 주문의 장바구니 대조가 겹치지 않게.
     * 돌려준 목록은 잠금을 얻은 뒤의 최신이다: 앞선 쓰기가 기다리는 사이 기존 줄보다 앞(유일 키 순서)에 넣은 줄도 들어 있다.
     */
    List<CartLine> lockByCustomer(UUID customerId);

    /** 그 회원의 그 줄을 잠가 읽는다. 남의 줄 · 없는 줄이면 비어 있다. */
    Optional<CartLine> lockLine(UUID customerId, UUID lineId);

    long countByCustomer(UUID customerId);

    /**
     * 새 줄. 넣자마자 flush 해 유일 키 충돌을 이 자리에서 잡는다.
     *
     * @throws com.grandis.nova.order.cart.domain.exception.CartSlotTakenException 같은 (회원, 옵션, 보증)이 이미 있다
     */
    CartLine insert(UUID customerId, UUID optionId, boolean warranty, int quantity);

    /** 수량을 바꾼다. 영향 행 수. */
    int changeQuantity(UUID lineId, int quantity, Instant now);

    /** 그 회원의 그 줄을 지운다. 영향 행 수(남의 줄 · 없는 줄이면 0). */
    int delete(UUID customerId, UUID lineId);
}
