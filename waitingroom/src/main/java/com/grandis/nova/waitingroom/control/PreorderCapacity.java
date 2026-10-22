package com.grandis.nova.waitingroom.control;

import java.util.Map;
import java.util.Optional;

/**
 * 살아 있는 preorder 태스크의 초당 접수 용량 합. 행 값은 "마지막시각|처음시각|초당용량|판정초"(Redis 초)다
 * — contracts/preorder-capacity-heartbeat.md. 행은 읽기만 하고, 지우는 것은 preorder 다.
 *
 * @param sum     용량 합(갓 뜬 태스크는 줄여 센 값). 운영 최댓값을 넘지 않는다
 * @param ready   다 뜬 태스크 수
 * @param warming 갓 뜬 태스크 수
 */
record PreorderCapacity(long sum, int ready, int warming) {

    /**
     * 모양이 틀린 행, 판정초가 지난 행, 마지막 시각이 지금보다 뒤인 행은 버린다.
     *
     * @return 남은 행이 없으면 빈 값 — 이때는 운영값만 쓴다
     */
    static Optional<PreorderCapacity> of(Map<String, String> rows, long nowSec, CapacityProperties properties,
                                         long maxCredit) {
        long warmupSec = properties.warmup().toSeconds();
        long sum = 0;
        int ready = 0;
        int warming = 0;
        for (String value : rows.values()) {
            Optional<Row> row = Row.parse(value);
            if (row.isEmpty() || !row.get().aliveAt(nowSec)) {
                continue;
            }
            long capacity = Math.min(row.get().perSecond(), maxCredit);
            if (nowSec - row.get().first() < warmupSec) {
                warming++;
                capacity = Math.max(1, (long) Math.floor(capacity * properties.warmupShare()));
            } else {
                ready++;
            }
            sum = Math.min(maxCredit, sum + capacity);
        }
        return ready + warming == 0 ? Optional.empty() : Optional.of(new PreorderCapacity(sum, ready, warming));
    }

    private record Row(long last, long first, long perSecond, long staleAfter) {

        static Optional<Row> parse(String value) {
            String[] parts = value == null ? new String[0] : value.split("\\|", -1);
            if (parts.length != 4) {
                return Optional.empty();
            }
            try {
                Row row = new Row(Long.parseLong(parts[0]), Long.parseLong(parts[1]), Long.parseLong(parts[2]),
                        Long.parseLong(parts[3]));
                return row.first() >= 0 && row.first() <= row.last() && row.perSecond() >= 1 && row.staleAfter() >= 1
                        ? Optional.of(row) : Optional.empty();
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }

        boolean aliveAt(long nowSec) {
            return last <= nowSec && nowSec - last <= staleAfter;
        }
    }
}
