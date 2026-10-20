package com.grandis.nova.batch.support;

import com.grandis.nova.batch.reconciliation.check.Check;
import com.grandis.nova.batch.reconciliation.check.FindingSink;
import com.grandis.nova.batch.reconciliation.check.KeyPair;
import com.grandis.nova.batch.reconciliation.check.RunScope;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 시험용 확인할 것 — 넘겨받은 키를 기록하고, 지정한 키는 불일치(TEST_CODE)로 남긴다.
 * 비교 규칙(NV-444)이 없어도 맞대기 · 결과 넣기 틀을 시험할 수 있게 한다.
 */
public class RecordingCheck implements Check {

    public static final String CODE = "TEST_CODE";

    private final List<String> keys = new CopyOnWriteArrayList<>();
    private final Set<String> flagged = ConcurrentHashMap.newKeySet();

    @Override
    public void inspect(KeyPair pair, RunScope scope, FindingSink findings) {
        keys.add(pair.key());
        if (flagged.contains(pair.key())) {
            findings.add(CODE, pair.key(), pair.be(), pair.mock());
        }
    }

    public List<String> keys() {
        return List.copyOf(keys);
    }

    public void flag(String key) {
        flagged.add(key);
    }

    public void reset() {
        keys.clear();
        flagged.clear();
    }
}
