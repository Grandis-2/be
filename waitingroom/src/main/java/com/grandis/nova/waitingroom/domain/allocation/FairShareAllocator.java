package com.grandis.nova.waitingroom.domain.allocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 전역 속도를 모델에 나눈다. 굶주린 모델에 균등하게 나누고 못 쓴 몫을 다시 굶주린 쪽으로 돌린다 —
 * 균등만이면 한산한 모델이 남긴 몫이 버려지고, 비례만이면 몰리는 모델 하나가 전부 가져간다.
 */
public class FairShareAllocator {

    /**
     * 균등하게 못 나눈 나머지는 굶주린 모델에 rotation 에서 시작해 한 명씩 준다 — 배분은 리더 한 곳만 하므로 총합이 넘지 않고,
     * 틱마다 시작을 돌려 같은 모델만 받지 않게 한다(순서는 키로 고정). 버리면 모델이 전역 속도보다 많을 때 모두 0 이 된다.
     */
    public List<Grant> allocate(long globalCredit, List<ProductDemand> demands, long rotation) {
        if (demands.stream().map(ProductDemand::productKey).distinct().count() != demands.size()) {
            throw new IllegalArgumentException("같은 모델의 요구가 둘 이상이다");
        }
        List<ProductDemand> active = demands.stream().filter(ProductDemand::isActive)
                .sorted(Comparator.comparing(ProductDemand::productKey)).toList();
        if (active.isEmpty()) {
            return List.of();
        }
        long[] granted = new long[active.size()];
        long pool = Math.max(0, globalCredit);
        // 몫이 굶주린 수보다 적어지면 더 못 나누므로 멎는다
        while (pool > 0) {
            long left = distribute(active, granted, pool);
            if (left == pool) {
                break;
            }
            pool = left;
        }
        List<Integer> hungry = new ArrayList<>();
        for (int i = 0; i < active.size(); i++) {
            if (granted[i] < active.get(i).want()) {
                hungry.add(i);
            }
        }
        // 남은 몫은 굶주린 수보다 적다. 이미 다 받은 모델 차례를 건너뛰면 다음 모델이 두 번 받으므로 굶주린 쪽에서만 돈다
        for (int step = 0; step < hungry.size() && pool > 0; step++) {
            granted[hungry.get((int) Math.floorMod(rotation + step, (long) hungry.size()))]++;
            pool--;
        }
        List<Grant> result = new ArrayList<>(active.size());
        for (int i = 0; i < active.size(); i++) {
            result.add(new Grant(active.get(i).productKey(), granted[i]));
        }
        return result;
    }

    /** 굶주린 모델에게 균등하게 나눠 주고 다음 패스로 넘길 몫을 돌려준다. */
    private long distribute(List<ProductDemand> active, long[] granted, long pool) {
        int hungry = 0;
        for (int i = 0; i < active.size(); i++) {
            if (granted[i] < active.get(i).want()) {
                hungry++;
            }
        }
        long share = hungry == 0 ? 0 : pool / hungry;
        if (share == 0) {
            return pool;
        }
        long spent = 0;
        for (int i = 0; i < active.size(); i++) {
            long room = active.get(i).want() - granted[i];
            if (room <= 0) {
                continue;
            }
            long give = Math.min(room, share);
            granted[i] += give;
            spent += give;
        }
        return pool - spent;
    }
}
