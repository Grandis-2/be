package com.grandis.nova.catalog.outbox;

/** 이벤트가 가리키는 대상. catalog_outbox_events.aggregate_type 에 이름 그대로 들어가고 봉투의 aggregateType 이 된다(대문자 — preorder 의 "PREORDER" 와 같은 표기). */
public enum AggregateType {
    PRODUCT
}
