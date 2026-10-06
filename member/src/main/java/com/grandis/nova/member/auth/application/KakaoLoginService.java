package com.grandis.nova.member.auth.application;

import com.grandis.nova.common.security.Role;
import com.grandis.nova.member.auth.infrastructure.kakao.KakaoOAuthClient;
import com.grandis.nova.member.auth.infrastructure.kakao.KakaoUserInfo;
import com.grandis.nova.member.customer.Customer;
import com.grandis.nova.member.customer.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 로그인 흐름. 카카오 호출 두 번은 트랜잭션 밖(외부 HTTP 가 커넥션을 5초씩 잡지 않게), 회원 INSERT 만 저장소 트랜잭션 안.
 *
 * 첫 로그인이 곧 가입이다. 같은 카카오 회원이 동시에 첫 로그인하면 둘 다 "없음" 을 보고 INSERT 를 시도하고 하나는
 * uq_customer_kakao(1062)에 걸린다. 그건 정상 분기다 — 진 쪽은 다시 조회해 이긴 쪽 행을 쓴다(ERD 의 "UNIQUE 최종 방어, 1062 정상 분기").
 * 재조회는 실패한 INSERT 의 트랜잭션 밖에서 한다. 플러시가 실패한 영속성 컨텍스트를 다시 쓰지 않기 위해서다.
 * 이 "밖" 은 `spring.jpa.open-in-view=false` 에 기댄다 — 이 클래스에 @Transactional 이 없고 OSIV 가 꺼져 있어야 저장소 호출마다
 * 트랜잭션과 영속성 컨텍스트가 갈린다(Boot 기본은 true). ConfigBindingTest 가 그 설정을 단언한다. 켜졌을 때 실제로 어떻게 되는지는 재지 않았다.
 * 실측: MemberIntegrationTest.concurrentFirstLoginCreatesOneRow (catch 진입 로그를 센다).
 */
@Service
public class KakaoLoginService {

    private static final Logger log = LoggerFactory.getLogger(KakaoLoginService.class);

    private final KakaoOAuthClient kakao;
    private final CustomerRepository customers;
    private final TokenService tokens;

    public KakaoLoginService(KakaoOAuthClient kakao, CustomerRepository customers, TokenService tokens) {
        this.kakao = kakao;
        this.customers = customers;
        this.tokens = tokens;
    }

    public LoginResult login(String code, String redirectUri, ClientInfo client) {
        String kakaoAccessToken = kakao.exchangeCode(code, redirectUri);
        KakaoUserInfo user = kakao.fetchUser(kakaoAccessToken);

        Customer customer = findOrCreate(user);

        // 제재 칸이 생기기 전까지 로그인은 막지 않는다. nbf 확인은 필터가 한다. 여기서는 발급만.
        TokenService.IssuedTokens issued = tokens.issue(String.valueOf(customer.getId()), Role.USER, client);
        return new LoginResult(issued, customer.getDisplayName(), Role.USER, customer.profile().isComplete());
    }

    private Customer findOrCreate(KakaoUserInfo user) {
        return customers.findByKakaoId(user.id()).orElseGet(() -> {
            try {
                return customers.saveAndFlush(Customer.fromKakao(user.id(), user.nickname()));   // 저장소 메서드가 자기 트랜잭션
            } catch (DataIntegrityViolationException e) {
                log.info("customers insert lost the race on kakao_id; reusing the existing row");
                return customers.findByKakaoId(user.id()).orElseThrow(() -> e);
            }
        });
    }

    /** `profileComplete` 가 false 면 화면이 내 정보 입력을 먼저 받는다. 가입 직후에는 언제나 false 다. */
    public record LoginResult(TokenService.IssuedTokens tokens, String displayName, Role role, boolean profileComplete) {
    }
}
