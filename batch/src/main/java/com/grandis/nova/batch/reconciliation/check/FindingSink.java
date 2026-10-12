package com.grandis.nova.batch.reconciliation.check;

import com.grandis.nova.batch.reconciliation.run.NewFinding;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * 이번 실행에서 찾은 불일치를 모은다. 실행이 끝나면 한 번에 넣는다(완료와 같은 트랜잭션).
 * 한 실행에서 같은 (코드, 대상)은 한 번만 남는다 — 테이블의 UNIQUE(run_id, check_code, target_key)와 같다.
 * 검사 때 본 값은 JSON 으로 남긴다. 실행 뒤에 데이터가 바뀌어도 그때 본 값이 화면에 나온다.
 */
public final class FindingSink {

    private final JsonMapper json;
    private final Map<String, NewFinding> found = new LinkedHashMap<>();

    FindingSink(JsonMapper json) {
        this.json = json;
    }

    /**
     * @param be   검사 때 본 be 값. 없으면 null
     * @param mock 검사 때 본 Mock 원장 행. 없으면 null
     */
    public void add(String checkCode, String targetKey, Object be, Object mock) {
        found.putIfAbsent(checkCode + '\u0000' + targetKey,
                new NewFinding(checkCode, targetKey, write(be), write(mock)));
    }

    public int size() {
        return found.size();
    }

    List<NewFinding> findings() {
        return new ArrayList<>(found.values());
    }

    private String write(Object value) {
        return value == null ? null : json.writeValueAsString(value);
    }
}
