package com.grandis.nova.order.draw.domain.model;

import java.time.Instant;

/**
 * 회차의 추첨 표식만(PK 로 읽는다 — 응모를 세지 않는다).
 *
 * @param drawRequestedAt Mock 에 추첨을 요청한 시각. 전원 당첨이면 비어 있다
 * @param settledAt       결과를 반영한 시각
 */
public record DrawCloseState(Instant drawRequestedAt, Instant settledAt) {

    /** 추첨 대상이 이미 정해졌는가 — 요청했거나 반영했다. 이 뒤에 결제 완료가 된 응모는 추첨에 들어가지 않는다. */
    public boolean drawn() {
        return drawRequestedAt != null || settledAt != null;
    }
}
