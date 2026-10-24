package com.grandis.nova.preorder.reconciliation.application;

import com.grandis.nova.preorder.reconciliation.domain.FindingRow;

/** @param productName 회차 사본의 상품 이름. 상품을 모르거나 회차가 없으면 null */
public record FindingView(FindingRow finding, String productName) {
}
