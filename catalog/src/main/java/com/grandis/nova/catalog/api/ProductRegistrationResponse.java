package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.detail.ProductDetailView;
import com.grandis.nova.catalog.registration.RegistrationStatusView;

/** 등록 응답 — 진행 상태와, 201 일 때만 관리자 미리보기(상세와 같은 모양). 200 · 202 는 product 가 null 이다. */
public record ProductRegistrationResponse(RegistrationStatusView registration, ProductDetailView product) {
}
