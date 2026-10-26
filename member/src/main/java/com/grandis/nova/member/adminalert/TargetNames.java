package com.grandis.nova.member.adminalert;

import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.member.adminalert.AdminAlertRules.TargetKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.UUID;

/**
 * 알림 대상(상품 · 드로우 회차)의 이름. 표시용이라 못 받아도 알림은 저장한다 — 받지 못하면 null 이고 정리 작업이 하루 안에 다시 묻는다.
 *
 * NV-810 의 MemberNames 와 달리 캐시 · 서킷 브레이커를 두지 않는다. 새 사건을 넣을 때만 부르고(하루 수십 번) 요청 스레드가 아니라 큐 소비 ·
 * 정리 스레드에서 부르기 때문이다. 기다림은 클라이언트 그룹의 시간 제한(연결 300ms · 읽기 1s, MemberDefaults)까지다.
 * 404(비공개 · 미완료 상품, 없는 회차)도 "없음"으로 기억하지 않는다 — 공개로 바뀌면 다시 채우기에서 채워진다.
 */
@Component
class TargetNames {

    private static final Logger log = LoggerFactory.getLogger(TargetNames.class);

    /** HTTP 클라이언트 그룹(spring.http.serviceclient.&lt;이름&gt;)의 이름 */
    static final String CATALOG = "catalog";
    static final String ORDER = "order";
    static final int MAX_LENGTH = 200;

    private final TargetClients.CatalogProductClient products;
    private final TargetClients.DrawClient draws;

    TargetNames(TargetClients.CatalogProductClient products, TargetClients.DrawClient draws) {
        this.products = products;
        this.draws = draws;
    }

    /**
     * 받은 결과. name 이 null 이면 이름이 없다 — failed 면 상대 장애 · 시간 초과 같은 실패이고, 아니면 공개 조회에 없는 대상이다.
     */
    record Lookup(String name, boolean failed) {

        static final Lookup ABSENT = new Lookup(null, false);
        static final Lookup FAILED = new Lookup(null, true);
    }

    /** 예외를 던지지 않는다. 대상이 상품 · 회차가 아니면 ABSENT. */
    Lookup find(TargetKind kind, String targetId) {
        if (kind != TargetKind.PRODUCT && kind != TargetKind.DRAW) {
            return Lookup.ABSENT;
        }
        UUID id;
        try {
            id = UUID.fromString(targetId);
        } catch (IllegalArgumentException e) {
            log.warn("대상 id 가 UUID 가 아니라 이름을 묻지 않는다 kind={} id={}", kind, targetId);
            return Lookup.ABSENT;
        }
        try {
            ApiResponse<TargetClients.Titled> response = kind == TargetKind.PRODUCT ? products.product(id) : draws.draw(id);
            String title = response == null || response.data() == null ? null : response.data().title();
            return title == null || title.isBlank() ? Lookup.ABSENT : new Lookup(cut(title), false);
        } catch (HttpClientErrorException.NotFound e) {
            log.info("대상이 공개 조회에 없다(비공개 · 미완료) — 이름 없이 둔다 kind={} id={}", kind, id);
            return Lookup.ABSENT;
        } catch (RestClientException e) {
            // 메시지는 싣지 않는다 — 응답 본문이 든다
            String status = e instanceof RestClientResponseException response ? " " + response.getStatusCode().value() : "";
            log.warn("대상 이름 조회 실패 — 이름 없이 둔다 kind={} id={} {}{}", kind, id, e.getClass().getSimpleName(), status);
            return Lookup.FAILED;
        } catch (RuntimeException e) {
            // 우리 쪽 결함(주소 설정 누락 · 응답 모양 등)이다. 이름 없이 두되 스택을 남긴다
            log.warn("대상 이름 조회 중 예상하지 못한 오류 — 이름 없이 둔다 kind={} id={}", kind, id, e);
            return Lookup.FAILED;
        }
    }

    private static String cut(String title) {
        return title.codePointCount(0, title.length()) <= MAX_LENGTH ? title
                : title.substring(0, title.offsetByCodePoints(0, MAX_LENGTH));
    }
}
