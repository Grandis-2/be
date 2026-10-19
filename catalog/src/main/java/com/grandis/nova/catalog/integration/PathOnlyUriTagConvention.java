package com.grandis.nova.catalog.integration;

import io.micrometer.common.KeyValue;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention;

/**
 * 내부 API 호출 지표(http.client.requests)의 uri 태그에서 쿼리 문자열을 뗀다. HTTP 인터페이스는 목록 파라미터를 원소마다
 * {@code {queryParam-optionIds}={queryParam-optionIds[i]}} 로 펼친 템플릿을 만들어, 기본 규칙이면 재고 조회 태그가 묻는 옵션 수마다 다른 값이 되고
 * 41개부터는 2048자를 넘어 Mimir 가 그 지표를 거절한다(100개면 5006자. OTel 에이전트의 Micrometer 브리지로 보낸다). 이름(management.observations.http.client.requests.name) · 다른 태그는 기본 규칙 그대로다.
 */
class PathOnlyUriTagConvention extends DefaultClientRequestObservationConvention {

    PathOnlyUriTagConvention(String name) {
        super(name);
    }

    @Override
    protected KeyValue uri(ClientRequestObservationContext context) {
        KeyValue uri = super.uri(context);
        int query = uri.getValue().indexOf('?');
        return query < 0 ? uri : KeyValue.of(uri.getKey(), uri.getValue().substring(0, query));
    }
}
