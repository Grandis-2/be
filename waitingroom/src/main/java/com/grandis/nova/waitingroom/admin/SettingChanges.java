package com.grandis.nova.waitingroom.admin;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.waitingroom.admin.SettingChangesView.Change;
import com.grandis.nova.waitingroom.admin.SettingChangesView.Kind;
import com.grandis.nova.waitingroom.control.OperationalSettings;
import com.grandis.nova.waitingroom.redis.ControlStore;
import com.grandis.nova.waitingroom.redis.ControlStore.SettingChange;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** 운영값 변경 이력을 읽어 관리자 응답으로 바꾼다. 남기는 쪽은 운영값을 쓰는 스크립트다. */
@Component
class SettingChanges {

    private static final Pattern CURSOR = Pattern.compile("\\d{1,19}-\\d{1,19}");
    private static final Instant LATEST = Instant.parse("9999-12-31T23:59:59Z");

    private final ControlStore store;
    private final ChangeLogProperties properties;

    SettingChanges(ControlStore store, ChangeLogProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    /** 한 건 더 읽어 다음 쪽이 있는지 본다. */
    Mono<SettingChangesView> read(String from, String to, String cursor, String size) {
        Instant fromAt = instant(blankToNull(from), "from");
        Instant toAt = instant(blankToNull(to), "to");
        String before = blankToNull(cursor);
        if (fromAt != null && toAt != null && fromAt.isAfter(toAt)) {
            throw invalid("from", "from 은 to 보다 늦을 수 없습니다.");
        }
        if (before != null && !CURSOR.matcher(before).matches()) {
            throw invalid("cursor", "cursor 가 올바르지 않습니다.");
        }
        int count = size(blankToNull(size));
        return store.readSettingChanges(fromAt, toAt, before, count + 1)
                .map(rows -> {
                    List<SettingChange> page = rows.size() > count ? rows.subList(0, count) : rows;
                    String next = rows.size() > count ? page.getLast().id() : null;
                    return new SettingChangesView(page.stream().map(SettingChanges::change).flatMap(Optional::stream)
                            .toList(), next);
                })
                .onErrorMap(OperationalSettingsAdmin::storeFailure, OperationalSettingsAdmin::unavailable);
    }

    /** 모르는 칸은 건너뛴다 — 이 노드보다 새 버전이 더한 운영값이다. */
    private static Optional<Change> change(SettingChange row) {
        Map<String, String> fields = row.fields();
        String field = fields.getOrDefault("field", "");
        Kind kind;
        String productId = null;
        switch (field) {
            case OperationalSettings.GLOBAL_CREDIT -> kind = Kind.GLOBAL_CREDIT;
            case OperationalSettings.MAX_WAIT_SEC -> kind = Kind.MAX_WAIT;
            case OperationalSettings.LEAVE_GRACE_SEC -> kind = Kind.LEAVE_GRACE;
            default -> {
                if (!field.startsWith("cap:")) {
                    return Optional.empty();
                }
                kind = Kind.PRODUCT_CREDIT;
                productId = field.substring(4);
            }
        }
        return Optional.of(new Change(row.at(), fields.get("by"), kind, productId, number(fields.get("from")),
                number(fields.get("to")), "1".equals(fields.get("confirmed"))));
    }

    /** 운영값은 쓸 때 정수만 받는다. 손으로 넣은 깨진 값은 리더가 기본값으로 읽으니 null 로 둔다. */
    private static Long number(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int size(String size) {
        if (size == null) {
            return properties.pageSize();
        }
        try {
            int parsed = Integer.parseInt(size);
            if (parsed >= 1 && parsed <= properties.maxPageSize()) {
                return parsed;
            }
        } catch (NumberFormatException e) {
            // 아래에서 범위 오류로 답한다
        }
        throw invalid("size", "size 는 1 이상 %d 이하의 정수여야 합니다.".formatted(properties.maxPageSize()));
    }

    /** 이력 id 는 1970 년부터 센 밀리초라 그 밖의 시각은 받지 않는다 — Redis 오류(503)나 변환 넘침(500)이 되지 않게. */
    private static Instant instant(String value, String field) {
        if (value == null) {
            return null;
        }
        try {
            Instant parsed = Instant.parse(value);
            if (!parsed.isBefore(Instant.EPOCH) && !parsed.isAfter(LATEST)) {
                return parsed;
            }
        } catch (DateTimeParseException e) {
            // 아래에서 형식 오류로 답한다
        }
        throw invalid(field, field + " 는 1970 년 이후 9999 년 이전의 ISO-8601 시각(예: 2026-10-24T00:00:00Z)이어야 합니다.");
    }

    /** 화면이 빈 값을 그대로 붙여 보내도(?cursor=) 없는 것으로 본다. */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static BusinessException invalid(String field, String message) {
        return new BusinessException(CommonErrorCode.VALIDATION_FAILED, message, Map.of("field", field));
    }
}
