package com.grandis.nova.waitingroom.admin;

import java.time.Instant;
import java.util.List;

/**
 * 운영값 변경 이력. 최신부터 온다.
 *
 * @param nextCursor 다음 쪽을 받을 커서. 마지막 쪽이면 null
 */
public record SettingChangesView(List<Change> items, String nextCursor) {

    /**
     * @param productId 모델별 상한이면 그 상품, 아니면 null
     * @param from      바꾸기 전 운영값. null 이면 기본값이었다
     * @param to        바꾼 운영값. null 이면 기본값으로 되돌렸다
     * @param confirmed 권장 상한을 넘겨 확인을 받고 바꿨다(전역 속도에서만 true 가 된다)
     */
    public record Change(Instant at, String by, Kind kind, String productId, Long from, Long to, boolean confirmed) {
    }

    public enum Kind {
        GLOBAL_CREDIT,
        PRODUCT_CREDIT,
        LEAVE_GRACE,
        MAX_WAIT
    }
}
