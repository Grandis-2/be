package com.grandis.nova.member.auth.api;

import com.grandis.nova.common.security.JwtKeyRing;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 발급 서비스의 공개키 게시(RFC 7517). 검증 서비스(catalog·preorder·order)가 `jwt.jwk-set-uri` 로 받아 캐시한다.
 * 공개 경로이고 봉투를 씌우지 않는다 — JWKS 는 표준 문서 형식이라 그대로 내야 다른 도구도 읽는다.
 *
 * 키 교체 순서: ① 새 키쌍 생성 ② member 의 `jwt.public-keys` 에 새 공개키(새 kid)를 넣어 배포하고 **JwtKeyRing.PERIODIC_REFRESH(5분) 이상 기다린다** —
 * 검증 서비스는 그 주기로 백그라운드에서 JWKS 를 받으므로 떠 있는 인스턴스도 새 kid 를 미리 갖는다 ③ `private-key`·`key-id` 를 새 키로, `public-keys` 를
 * **옛** 공개키로 바꿔 배포 — JwtKeyRing 은 서명 kid 를 먼저 넣고 public-keys 는 putIfAbsent 라, ②의 새 공개키를 그대로 두면 옛 kid 가 링에서 사라져
 * 살아 있는 리프레시 14일치가 전부 죽는다 ④ 14일 뒤 옛 공개키 제거. ②를 건너뛰거나 5분을 안 기다리면 인스턴스마다 새 kid 를 처음 본 요청이 401 이고
 * 그 요청이 백그라운드 갱신을 건다(요청 스레드는 기다리지 않는다).
 */
@RestController
public class JwksController {

    private final JwtKeyRing keys;

    public JwksController(JwtKeyRing keys) {
        this.keys = keys;
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return keys.jwks();
    }
}
