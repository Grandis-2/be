package com.grandis.nova.payment.client.toss;

/** 토스 오류 응답 본문 { "code": ..., "message": ... }. */
record TossError(String code, String message) {
}
