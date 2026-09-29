package com.grandis.nova.order.web;

/**
 * 조회하는 주체. 관리자는 회원이 아니라 회원 id 가 없다.
 *
 * @param customerId USER 면 그 회원, ADMIN 이면 null
 */
public record Viewer(Long customerId, boolean admin) {

    /** 관리자는 모두, 회원은 자기 것만 본다. */
    public boolean canSee(Long ownerId) {
        return admin || ownerId.equals(customerId);
    }
}
