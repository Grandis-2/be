package com.grandis.nova.preorder.syncjob.domain;

/** 마지막 시도의 errorCode 로 묶은 작업 수. 시도가 없으면 errorCode 가 null 이다. */
public record ErrorGroup(String errorCode, long count) {
}
