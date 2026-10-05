package com.grandis.nova.waitingroom.relay;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

/** @param preorderUri 접수를 전달할 preorder 주소. 로컬 기본값은 preorder 의 기본 포트다 */
@ConfigurationProperties("waitingroom.relay")
public record RelayProperties(URI preorderUri) {

    public RelayProperties {
        preorderUri = preorderUri == null ? URI.create("http://localhost:8083") : preorderUri;
    }
}
