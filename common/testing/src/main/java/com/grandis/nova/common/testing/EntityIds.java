package com.grandis.nova.common.testing;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 엔티티 id 가 UUID 인지 확인한다. 서비스 모듈의 시험이 자기 패키지를 넘겨 부른다.
 *
 * 모든 엔티티는 UUID 인 @Id 를 하나 이상 가져야 한다. UUID 가 아닌 @Id 는 복합키의 순번처럼 UUID @Id 옆에서만 허용한다 —
 * Long id 하나짜리 엔티티가 새로 생기면 여기서 막힌다. 부모 클래스(@MappedSuperclass)의 @Id 와 @EmbeddedId 클래스의 칸도 센다.
 */
public final class EntityIds {

    private EntityIds() {
    }

    public static void assertUuidIds(String basePackage) {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

        List<String> entities = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents(basePackage)) {
            Class<?> type = load(candidate.getBeanClassName());
            entities.add(type.getSimpleName());
            List<Field> ids = idFields(type);
            boolean hasUuid = ids.stream().anyMatch(f -> f.getType() == UUID.class);
            if (!hasUuid) {
                violations.add(type.getName() + " 의 @Id " + ids.stream().map(f -> f.getName() + ":" + f.getType().getSimpleName()).toList());
            }
        }
        if (entities.isEmpty()) {
            throw new AssertionError(basePackage + " 에서 @Entity 를 찾지 못했다 — 패키지 이름을 확인한다");
        }
        if (!violations.isEmpty()) {
            throw new AssertionError("UUID @Id 가 없는 엔티티:\n  " + String.join("\n  ", violations));
        }
    }

    private static List<Field> idFields(Class<?> type) {
        List<Field> ids = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (field.isAnnotationPresent(Id.class)) {
                    ids.add(field);
                } else if (field.isAnnotationPresent(EmbeddedId.class)) {
                    ids.addAll(List.of(field.getType().getDeclaredFields()));
                }
            }
        }
        return ids;
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(name, e);
        }
    }
}
