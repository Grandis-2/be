package com.grandis.nova.preorder.integration.member;

import com.grandis.nova.common.web.ApiResponse;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * preorder → member 내부 API 호출 클라이언트(선언형 HTTP 인터페이스, 구현은 MemberClientConfig 가 만든다). 회원 이름은 member 에게 묻는다 —
 * customers 를 직접 읽지 않는다(모듈 경계). 계약: contracts/member-internal.md 의 GET /internal/customers/names.
 * 관리자 요청의 토큰을 그대로 싣는다(TokenRelayConfig) — member 는 ADMIN 토큰만 받는다.
 */
@HttpExchange("/internal/customers")
public interface MemberClient {

    /** @param ids 회원 id 1~100개. 없는 회원은 결과에서 빠진다 */
    @GetExchange("/names")
    ApiResponse<Names> getNames(@RequestParam("ids") Collection<UUID> ids);

    record Names(List<Name> items) {
    }

    /** @param name 가린 이름(예: 김**) — 가림 규칙은 member 가 정한다 */
    record Name(UUID memberId, String name) {
    }
}
