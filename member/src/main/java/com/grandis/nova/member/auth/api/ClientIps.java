package com.grandis.nova.member.auth.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 요청을 보낸 실제 클라이언트 IP. 운영은 CloudFront → ALB → member 라(인프라 도면) remoteAddr 은 ALB 이고, 실제 IP 는 X-Forwarded-For 에 있다.
 *
 * <p><b>믿을 수 있는 것은 우리 프록시가 붙인 칸뿐이다.</b> X-Forwarded-For 는 클라이언트가 처음부터 아무 값이나 넣어 보낼 수 있고, 각 프록시는
 * 그 뒤에 자기가 본 주소를 이어 붙인다. CloudFront 가 실제 접속 IP 를, ALB 가 CloudFront 엣지 IP 를 붙이므로 오른쪽에서 {@code trustedProxyHops}
 * 번째 칸이 실제 클라이언트다(기본 2). 그 왼쪽은 위조할 수 있으니 쓰지 않는다 — 맨 왼쪽을 쓰면 요청마다 값을 바꿔 IP 별 제한을 없는 것으로 만든다.
 * 칸이 모자라면(프록시를 안 거친 요청 · 로컬) remoteAddr 을 쓴다. 0 이면 헤더를 보지 않는다.
 *
 * <p>이 계산은 ALB 가 CloudFront 에서 온 요청만 받는다는 전제(보안 그룹의 CloudFront prefix list)에 기댄다. 앞단 프록시 수가 바뀌면 이 값도 같이
 * 바꾼다 — 실제보다 크게 잡으면 위조한 칸을 읽는다.
 */
public final class ClientIps {

    static final String FORWARDED_FOR = "X-Forwarded-For";

    private final int trustedProxyHops;

    public ClientIps(int trustedProxyHops) {
        if (trustedProxyHops < 0) {
            throw new IllegalArgumentException("trusted-proxy-hops must be >= 0, was " + trustedProxyHops);
        }
        this.trustedProxyHops = trustedProxyHops;
    }

    public String of(HttpServletRequest request) {
        if (trustedProxyHops == 0) {
            return request.getRemoteAddr();
        }
        List<String> entries = new ArrayList<>();
        for (String header : Collections.list(request.getHeaders(FORWARDED_FOR))) {   // 헤더가 여러 줄로 와도 순서대로 이어 붙인 목록이다
            for (String entry : header.split(",")) {
                if (!entry.isBlank()) {
                    entries.add(entry.strip());
                }
            }
        }
        if (entries.size() < trustedProxyHops) {
            return request.getRemoteAddr();
        }
        return entries.get(entries.size() - trustedProxyHops);
    }
}
