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
 * @param reachable preorder 를 최대 대수로 늘리면 닿는 용량 — 지금 태스크를 다 센 합 + 남은 대수 × 가장 작은 태스크 용량.
 *                  태스크마다 용량이 달라도 늘어날 태스크를 크게 잡지 않는다. 운영 최댓값을 넘지 않는다
 * @param held      이번 회차에 읽은 값이 아니라 직전 값을 그대로 쓰는 중이다(표시가 모두 낡았거나 읽지 못함)
 */
record PreorderCapacity(long sum, int ready, int warming, long reachable, boolean held) {

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
        long full = 0;
        long smallest = Long.MAX_VALUE;
        for (String value : rows.values()) {
            Optional<Row> row = Row.parse(value);
            if (row.isEmpty() || !row.get().aliveAt(nowSec)) {
                continue;
            }
            long capacity = Math.min(row.get().perSecond(), maxCredit);
            full = Math.min(maxCredit, full + capacity);
            smallest = Math.min(smallest, capacity);
            if (nowSec - row.get().first() < warmupSec) {
                warming++;
                capacity = Math.max(1, (long) Math.floor(capacity * properties.warmupShare()));
            } else {
                ready++;
            }
            sum = Math.min(maxCredit, sum + capacity);
        }
        if (ready + warming == 0) {
            return Optional.empty();
        }
        long more = Math.max(0, properties.preorderMaxTasks() - (ready + warming));
        long added = more > 0 && smallest > maxCredit / more ? maxCredit : more * smallest;
        return Optional.of(new PreorderCapacity(sum, ready, warming, Math.min(maxCredit, full + added), false));
    }

    /** 직전 값을 그대로 쓴다는 표시를 붙인다. */
    PreorderCapacity asHeld() {
        return new PreorderCapacity(sum, ready, warming, reachable, true);
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
