package com.grandis.nova.member.adminalert;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.CursorPage;
import com.grandis.nova.member.adminalert.AdminAlertRules.Audience;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 관리자 알림 조회 · 읽음 표시. 읽음은 전역 하나다 — 관리자 계정이 환경 변수로 만든 하나(sub="admin")라 사람을 가르지 않는다. */
@Service
class AdminAlertService {

    private final AdminAlertQueries queries;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    AdminAlertService(AdminAlertQueries queries, JsonMapper jsonMapper, Clock clock) {
        this.queries = queries;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /** 최신순. 한 개 더 읽어 다음 쪽이 있는지 가른다 — 총계는 내지 않는다. */
    CursorPage<AdminAlertResponse> list(AdminAlertQueries.Filter filter, String cursor, int size) {
        List<AdminAlertQueries.View> found = queries.list(filter, AdminAlertCursor.decode(cursor), size + 1);
        if (found.size() <= size) {
            return CursorPage.last(found.stream().map(this::response).toList());
        }
        List<AdminAlertQueries.View> page = found.subList(0, size);
        return CursorPage.of(page.stream().map(this::response).toList(), AdminAlertCursor.encode(page.getLast()));
    }

    /** ADMIN · DEVELOPER 순서로 늘 둘 다 준다. */
    Map<Audience, AdminAlertResponse.UnreadCounts> unreadCounts() {
        Map<Audience, AdminAlertResponse.UnreadCounts> counts = new LinkedHashMap<>();
        queries.unreadCounts().forEach((audience, bySeverity) -> counts.put(audience, AdminAlertResponse.UnreadCounts.of(bySeverity)));
        return counts;
    }

    /** 이미 읽었으면 그대로 둔다. 없는 id 는 404. 바꾼 행이 없을 때만 있는지 확인한다. */
    void markRead(UUID id) {
        if (queries.markRead(id, now()) == 0 && !queries.exists(id)) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND, "알림을 찾을 수 없습니다.");
        }
    }

    void markAllRead(Audience audience) {
        queries.markAllRead(audience, now());
    }

    AdminAlertResponse response(AdminAlertQueries.View view) {
        return AdminAlertResponse.of(view, jsonMapper);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
