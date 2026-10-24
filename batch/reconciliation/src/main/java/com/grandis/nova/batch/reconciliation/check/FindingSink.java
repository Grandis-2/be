package com.grandis.nova.batch.reconciliation.check;

import com.grandis.nova.batch.reconciliation.run.NewFinding;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * 이번 실행에서 찾은 불일치를 모은다. 실행이 끝나면 한 번에 넣는다(완료와 같은 트랜잭션).
 * 한 실행에서 같은 (코드, 대상)은 한 번만 남는다 — 테이블의 UNIQUE(run_id, check_code, target_key)와 같다.
 * 검사 때 본 값은 JSON 으로 남긴다. 실행 뒤에 데이터가 바뀌어도 그때 본 값이 화면에 나온다.
 * 상한을 넘으면 더 모으지 않고 {@link TooManyFindings} 를 던진다 — 메모리와 완료 트랜잭션이 예약 수만큼 커지지 않게.
 */
public final class FindingSink {

    private final JsonMapper json;
    private final int max;
    private final Map<String, NewFinding> found = new LinkedHashMap<>();

    FindingSink(JsonMapper json, int max) {
        this.json = json;
        this.max = max;
    }

    /**
     * @param productId 불일치 대상의 상품(UUID 문자열). 알 수 없거나 UUID 가 아니면(Mock 이 준 값) 비워 둔다
     * @param be        검사 때 본 be 값. 없으면 null
     * @param mock      검사 때 본 Mock 원장 행. 없으면 null
     */
    public void add(CheckCode code, String targetKey, String productId, Object be, Object mock) {
        add(code.name(), targetKey, productId, be, mock);
    }

    /** 코드를 문자열로 받는다 — 시험용 확인할 것이 코드 목록에 없는 값을 쓴다 */
    public void add(String checkCode, String targetKey, String productId, Object be, Object mock) {
        String key = checkCode + '\u0000' + targetKey;
        if (found.containsKey(key)) {
            return;
        }
        if (found.size() >= max) {
            throw new TooManyFindings(max);
        }
        found.put(key, new NewFinding(checkCode, targetKey, product(productId), write(be), write(mock)));
    }

    public int size() {
        return found.size();
    }

    List<NewFinding> findings() {
        return new ArrayList<>(found.values());
    }

    private static UUID product(String productId) {
        if (productId == null) {
            return null;
        }
        try {
            return UUID.fromString(productId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String write(Object value) {
        return value == null ? null : json.writeValueAsString(value);
    }
}
