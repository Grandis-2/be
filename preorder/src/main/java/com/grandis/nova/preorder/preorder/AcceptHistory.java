package com.grandis.nova.preorder.preorder;

import java.time.Instant;
import java.util.Optional;

/**
 * 접수 판정에 쓰는 회원의 앞선 접수({@link Preorders#findAcceptHistory}) — 같은 접수 키의 예약과 그 모델의 마지막 접수 시각.
 *
 * @param sameKey        그 접수 키로 만든 예약(취소된 것 포함)
 * @param lastAcceptedAt 그 모델의 마지막 접수 시각(취소된 예약 포함)
 */
public record AcceptHistory(Optional<PreorderSnapshot> sameKey, Optional<Instant> lastAcceptedAt) {
}
