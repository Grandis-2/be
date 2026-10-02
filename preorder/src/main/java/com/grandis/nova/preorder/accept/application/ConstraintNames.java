package com.grandis.nova.preorder.accept.application;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Locale;
import java.util.Optional;

/**
 * 제약 위반에서 제약 이름을 꺼낸다. 오류 메시지 문자열을 파싱하지 않고 Hibernate 가 추출한 이름을 쓴다.
 * MySQL 은 "preorders.uq_preorder_active" 처럼 표 이름을 앞에 붙일 수 있어 마지막 조각만 비교한다.
 */
final class ConstraintNames {

    private ConstraintNames() {
    }

    public static Optional<String> of(DataIntegrityViolationException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
                String name = violation.getConstraintName().replace("`", "");
                return Optional.of(name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT));
            }
        }
        return Optional.empty();
    }
}
