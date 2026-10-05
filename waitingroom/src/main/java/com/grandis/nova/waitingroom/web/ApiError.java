package com.grandis.nova.waitingroom.web;

import java.util.Map;

/** 실패 봉투의 error. details 는 없으면 null 이다. */
public record ApiError(String code, String message, Map<String, Object> details) {
}
