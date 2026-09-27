package com.grandis.nova.member.auth.api;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.security.AuthenticatedPrincipal;
import com.grandis.nova.common.security.InvalidTokenException;
import com.grandis.nova.common.security.JsonAuthFailureHandlers;
import com.grandis.nova.common.security.JwtAuthenticationFilter;
import com.grandis.nova.common.security.JwtTokenProvider;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.common.security.TokenClaims;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.member.auth.application.AdminLoginService;
import com.grandis.nova.member.auth.application.ClientInfo;
import com.grandis.nova.member.auth.application.KakaoLoginService;
import com.grandis.nova.member.auth.application.TokenService;
import com.grandis.nova.member.customer.CustomerRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * api-spec F-X-01. 경로·응답 필드·오류 코드는 계약 그대로다.
 *
 * - POST /auth/kakao/callback {code, redirectUri} → 200 {sessionToken, displayName, role} + 쿠키 refresh_token.
 *   프론트 주도 코드 교환: state 는 프론트가 만들고 프론트가 검증한다. 서버는 redirectUri 가 허용 목록에 있는지만 본다.
 * - POST /session/refresh (쿠키) → 200 같은 모양 + 새 쿠키. 공개 경로. Origin 허용 목록(RefreshOriginPolicy) → 회원 이름을 **먼저** 읽고(
 *   회전 뒤에 DB 가 죽으면 회전만 되고 쿠키를 못 줘 다음 시도가 재사용으로 찍힌다) → TokenService.rotate(폐기 검사 두 번 포함).
 * - GET /session → {displayName, role}. USER 면 customers 에서 이름을 읽는다.
 * - DELETE /session → 204 + 두 쿠키 만료. **공개 경로**: 만료된 액세스로도 로그아웃이 되어야 한다. 액세스 헤더·회원 쿠키·관리자 쿠키
 *   중 파싱되는 것의 sid 를 전부 폐기한다. 폐기 표식·리프레시 삭제 중 하나라도 저장소 장애로 못 했으면 **503 DEPENDENCY_UNAVAILABLE(details.retryable=true)**
 *   — 쿠키는 그래도 지워 이 브라우저는 로그아웃되지만, 서버 쪽 폐기가 안 끝난 것을 204 로 숨기지 않는다. 프론트는 액세스 헤더로 로그아웃을 다시 보낸다.
 * - POST /admin/session {username, password} → 200 {sessionToken, role: ADMIN} + 쿠키 admin_refresh_token / 401 INVALID_CREDENTIALS.
 */
@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final KakaoLoginService kakaoLogin;
    private final AdminLoginService adminLogin;
    private final TokenService tokens;
    private final JwtTokenProvider provider;
    private final CustomerRepository customers;
    private final AuthCookies cookies;
    private final RefreshOriginPolicy refreshOrigins;

    public AuthController(KakaoLoginService kakaoLogin, AdminLoginService adminLogin, TokenService tokens,
                          JwtTokenProvider provider, CustomerRepository customers, AuthCookies cookies, RefreshOriginPolicy refreshOrigins) {
        this.kakaoLogin = kakaoLogin;
        this.adminLogin = adminLogin;
        this.tokens = tokens;
        this.provider = provider;
        this.customers = customers;
        this.cookies = cookies;
        this.refreshOrigins = refreshOrigins;
    }

    public record KakaoCallbackRequest(@NotBlank String code, @NotBlank String redirectUri) {
    }

    public record AdminLoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    @PostMapping("/auth/kakao/callback")
    public ResponseEntity<ApiResponse<LoginResponse>> kakaoCallback(@Valid @RequestBody KakaoCallbackRequest request,
                                                                     HttpServletRequest servletRequest) {
        KakaoLoginService.LoginResult result = kakaoLogin.login(request.code(), request.redirectUri(), clientOf(servletRequest));
        return withRefreshCookie(result.tokens(), Role.USER, new LoginResponse(result.tokens().accessToken(), result.displayName(), result.role()));
    }

    @PostMapping("/session/refresh")
    public ResponseEntity<ApiResponse<LoginResponse>> refresh(HttpServletRequest request) {
        refreshOrigins.require(request);
        // 쿠키는 원문으로 읽는다 — @CookieValue 의 URL 디코딩이 잘못된 값에 500 을 냈다(AuthCookies.raw)
        String userRefresh = AuthCookies.raw(request, AuthCookies.REFRESH_TOKEN);
        String adminRefresh = AuthCookies.raw(request, AuthCookies.ADMIN_REFRESH_TOKEN);
        // 회원 쿠키가 있으면 그것을, 없으면 관리자 쿠키를. 둘 다 있으면 회원(한 브라우저에서 둘을 같이 쓰는 건 개발 중뿐이다).
        // 어느 쿠키로 왔는지가 곧 역할이다 — 회원 리프레시는 불투명 난수라 값 자체에는 주인도 역할도 적혀 있지 않다.
        Role role = present(userRefresh) ? Role.USER : Role.ADMIN;
        String refreshToken = present(userRefresh) ? userRefresh : adminRefresh;
        if (!present(refreshToken)) {
            throw new InvalidTokenException("refresh cookie missing");
        }
        // 실패할 수 있는 DB 조회(회원 이름)를 회전 **앞**에 둔다. 여기서 던지면 리프레시가 아직 교체되지 않아 같은 쿠키로 다시 올 수 있다.
        String subject = tokens.subjectOf(refreshToken, role);
        String displayName = displayNameOf(new AuthenticatedPrincipal(subject, role));
        TokenService.Rotated rotated = tokens.rotate(refreshToken, role, clientOf(request));
        return withRefreshCookie(rotated.tokens(), role, new LoginResponse(rotated.tokens().accessToken(), displayName, role));
    }

    @GetMapping("/session")
    public ApiResponse<SessionInfoResponse> session(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ApiResponse.ok(new SessionInfoResponse(displayNameOf(principal), principal.role()));
    }

    @DeleteMapping("/session")
    public ResponseEntity<ApiResponse<Void>> logout(
            HttpServletRequest request,
            @RequestHeader(name = JwtAuthenticationFilter.HEADER, required = false) String accessToken) {
        String userRefresh = AuthCookies.raw(request, AuthCookies.REFRESH_TOKEN);
        String adminRefresh = AuthCookies.raw(request, AuthCookies.ADMIN_REFRESH_TOKEN);
        Set<UUID> sessions = new LinkedHashSet<>();
        // 액세스·관리자 리프레시는 JWT 라 파싱하면 sid 가 나온다.
        for (String token : new String[] {accessToken, adminRefresh}) {
            if (!present(token)) {
                continue;
            }
            try {
                sessions.add(provider.parse(token).sessionId());
            } catch (InvalidTokenException e) {
                // 만료·위조된 토큰으로도 로그아웃은 된다. 끊을 sid 를 못 알아낼 뿐이고 쿠키는 어차피 지운다.
                log.info("logout: token ignored ({})", e.reason());
            }
        }
        // 회원 리프레시는 불투명 난수라 파싱할 것이 없다. 저장소에서 주인을 찾는다 — 폐기·만료된 행도 찾아 sid 를 얻는다.
        boolean incomplete = false;
        if (present(userRefresh)) {
            try {
                tokens.sessionOfUserRefresh(userRefresh).ifPresentOrElse(sessions::add,
                        () -> log.info("logout: refresh cookie matches no stored token"));
            } catch (DataAccessException e) {
                // 이 쿠키의 sid 는 못 알아냈다. 그래도 헤더에서 얻은 sid 는 끊고, 성공으로 답하지 않는다.
                // 여기서 던지면 나머지 폐기도 안 되고 쿠키도 안 지워진 채 500 이 나간다.
                incomplete = true;
                log.warn("logout: refresh lookup failed cause={}", e.getClass().getSimpleName());
            }
        }
        for (UUID sid : sessions) {
            try {
                tokens.revoke(sid);
            } catch (DataAccessException e) {
                // 저장소 장애. 쿠키는 지우되 성공으로 답하지 않는다 — 폐기 안 된 토큰이 남아 있다는 사실을 클라이언트가 알아야 다시 보낸다.
                // DataAccessException 만 여기서 받는다. 다른 예외는 500 으로 드러나야 한다.
                incomplete = true;
                log.warn("logout incomplete sid={} cause={}", sid.toString().substring(0, 8), e.getClass().getSimpleName());
            }
        }
        ResponseEntity.BodyBuilder response = ResponseEntity
                .status(incomplete ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.NO_CONTENT)
                .header(HttpHeaders.SET_COOKIE, cookies.expiredRefresh(Role.USER).toString())
                .header(HttpHeaders.SET_COOKIE, cookies.expiredRefresh(Role.ADMIN).toString());
        if (incomplete) {
            CommonErrorCode code = CommonErrorCode.DEPENDENCY_UNAVAILABLE;
            return response.body(ApiResponse.fail(code, code.defaultMessage(), JsonAuthFailureHandlers.RETRYABLE_DETAILS));
        }
        return response.build();
    }

    @PostMapping("/admin/session")
    public ResponseEntity<ApiResponse<AdminSessionResponse>> adminSession(@Valid @RequestBody AdminLoginRequest request,
                                                                          HttpServletRequest servletRequest) {
        TokenService.IssuedTokens issued = adminLogin.login(request.username(), request.password(), clientOf(servletRequest));
        return withRefreshCookie(issued, Role.ADMIN, new AdminSessionResponse(issued.accessToken(), Role.ADMIN));
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * 리프레시 행에 남길 요청 흔적. 인증 판정에는 쓰지 않는다 — "로그인된 기기" 표시와 사고 조사용이다.
     * 프록시 뒤에서 remoteAddr 은 로드밸런서 주소다. 실제 클라이언트 IP 를 남기려면 `server.forward-headers-strategy` 가 켜져 있어야 한다.
     */
    private static ClientInfo clientOf(HttpServletRequest request) {
        return new ClientInfo(request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT));
    }

    private <T> ResponseEntity<ApiResponse<T>> withRefreshCookie(TokenService.IssuedTokens issued, Role role, T body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.refresh(issued.refreshToken(), issued.refreshTokenMaxAge(), role).toString())
                .body(ApiResponse.ok(body));
    }

    private String displayNameOf(AuthenticatedPrincipal principal) {
        if (principal.role() != Role.USER) {
            return null;
        }
        long customerId;
        try {
            customerId = principal.customerId();
        } catch (NumberFormatException e) {
            // CurrentCustomerIdArgumentResolver 와 같은 처리: USER 의 sub 는 발급기가 customers.id 로만 만든다. 십진수가 아니면 500 이 아니라 401.
            throw new InvalidTokenException("non-numeric subject for USER");
        }
        // 행이 없으면 401 — CustomerService 와 같은 규칙. 탈퇴가 없어 지금은 도달하지 않는 갈래다.
        return customers.findById(customerId).map(c -> c.getDisplayName())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.UNAUTHENTICATED));
    }
}
