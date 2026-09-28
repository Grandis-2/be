package com.grandis.nova.member;

import com.grandis.nova.member.support.MemberIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.common.security.JwtAuthenticationFilter;
import com.grandis.nova.common.security.JwtTokenProvider;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.common.security.TokenType;
import com.grandis.nova.common.web.RequestIdFilter;
import com.grandis.nova.member.customer.Customer;
import com.grandis.nova.member.customer.CustomerRepository;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * GET/PUT /me/default-address (api-spec F-X-01 끝). 실제 MySQL — DDL 의 ck_customer_default_address 가 여기서 실측된다.
 * 카카오 없이 회원 행을 직접 만들고 발급기로 USER 토큰을 만든다. Redis 는 필터의 폐기 조회에 쓰인다(없으면 skip).
 */
@MemberIntegrationTest
@DisplayName("/me/default-address (실제 MySQL)")
class DefaultAddressIntegrationTest {

    private static final String PATH = "/api/v1/me/default-address";
    private static final String FULL = "{\"name\":\"홍길동\",\"phone\":\"01012345678\",\"postalCode\":\"06236\",\"line1\":\"서울시 강남구 예시로 1\",\"line2\":\"101호\"}";



    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy springSecurityFilterChain;
    @Autowired RequestIdFilter requestIdFilter;
    @Autowired JwtTokenProvider provider;
    @Autowired CustomerRepository customers;
    @Autowired JdbcTemplate jdbc;

    private MockMvc mvc;
    private Customer customer;
    private String userToken;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter, springSecurityFilterChain).build();
        customer = customers.saveAndFlush(Customer.fromKakao("t" + UUID.randomUUID().toString().replace("-", "").substring(0, 18), "홍길동"));
        userToken = provider.create(String.valueOf(customer.getId()), Role.USER, UUID.randomUUID(), TokenType.ACCESS);
    }

    @Test
    @DisplayName("미등록이면 shippingAddress 가 명시적 null 이다")
    void unregisteredIsNull() throws Exception {
        mvc.perform(get(PATH).header(JwtAuthenticationFilter.HEADER, userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shippingAddress").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data").exists());
    }

    @Test
    @DisplayName("PUT 전체 → 200 에 저장된 값, GET 왕복 같음, DB 다섯 칸이 채워짐. line2 를 비우면 null 로 저장")
    void putThenGetRoundTrip() throws Exception {
        mvc.perform(put(PATH).header(JwtAuthenticationFilter.HEADER, userToken).contentType(MediaType.APPLICATION_JSON).content(FULL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shippingAddress.name").value("홍길동"))
                .andExpect(jsonPath("$.data.shippingAddress.phone").value("01012345678"))
                .andExpect(jsonPath("$.data.shippingAddress.postalCode").value("06236"))
                .andExpect(jsonPath("$.data.shippingAddress.line1").value("서울시 강남구 예시로 1"))
                .andExpect(jsonPath("$.data.shippingAddress.line2").value("101호"));
        mvc.perform(get(PATH).header(JwtAuthenticationFilter.HEADER, userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shippingAddress.line2").value("101호"));
        assertThat(jdbc.queryForList("SELECT default_ship_to_name, default_ship_to_phone, default_ship_to_postal_code, default_ship_to_line1, default_ship_to_line2 FROM customers WHERE id = ?", customer.getId()).get(0).values())
                .containsExactly("홍길동", "01012345678", "06236", "서울시 강남구 예시로 1", "101호");

        // 상세주소만 비운 전체 교체: line2 = null, 나머지 넷은 NOT NULL — CHECK 가 허용하는 두 번째 모양
        mvc.perform(put(PATH).header(JwtAuthenticationFilter.HEADER, userToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" 김철수 \",\"phone\":\"01000000000\",\"postalCode\":\"12345\",\"line1\":\"부산시 해운대구 1\",\"line2\":\"  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shippingAddress.name").value("김철수"))
                .andExpect(jsonPath("$.data.shippingAddress.line2").value(org.hamcrest.Matchers.nullValue()));
        assertThat(jdbc.queryForObject("SELECT default_ship_to_line2 FROM customers WHERE id = ?", String.class, customer.getId())).isNull();
    }

    private static String body(String name, String phone, String postalCode, String line1, String line2) {
        return "{\"name\":\"" + name + "\",\"phone\":\"" + phone + "\",\"postalCode\":\"" + postalCode + "\",\"line1\":\"" + line1 + "\""
                + (line2 == null ? "" : ",\"line2\":\"" + line2 + "\"") + "}";
    }

    @Test
    @DisplayName("넷 중 하나가 빠지거나 다섯 칸 중 어느 하나라도 길이를 넘으면 DB 에 가기 전에 400 VALIDATION_FAILED, details.violations 에 그 칸 이름. 이미 있던 배송지는 그대로")
    void validationFailsBeforeDb() throws Exception {
        // 먼저 유효한 배송지를 두고, 잘못된 PUT 이 그것을 건드리지 않는지까지 본다
        mvc.perform(put(PATH).header(JwtAuthenticationFilter.HEADER, userToken).contentType(MediaType.APPLICATION_JSON).content(FULL)).andExpect(status().isOk());

        mvc.perform(put(PATH).header(JwtAuthenticationFilter.HEADER, userToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"홍길동\",\"postalCode\":\"06236\",\"line1\":\"서울\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[?(@.field == \'phone\')]").exists());
        // 칸마다 max+1 — 어느 @Size 를 지워도 여기서 걸린다
        String[][] tooLong = {
                {"name", body("가".repeat(51), "010", "1", "x", null)},
                {"phone", body("홍", "0".repeat(21), "1", "x", null)},
                {"postalCode", body("홍", "010", "1".repeat(11), "x", null)},
                {"line1", body("홍", "010", "1", "x".repeat(201), null)},
                {"line2", body("홍", "010", "1", "x", "y".repeat(201))},
        };
        for (String[] c : tooLong) {
            mvc.perform(put(PATH).header(JwtAuthenticationFilter.HEADER, userToken).contentType(MediaType.APPLICATION_JSON).content(c[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.error.details.violations[?(@.field == '" + c[0] + "')]").exists());
        }
        // max 그대로는 통과한다(경계)
        mvc.perform(put(PATH).header(JwtAuthenticationFilter.HEADER, userToken).contentType(MediaType.APPLICATION_JSON)
                        .content(body("가".repeat(50), "0".repeat(20), "1".repeat(10), "x".repeat(200), "y".repeat(200))))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT default_ship_to_name FROM customers WHERE id = ?", String.class, customer.getId())).isEqualTo("가".repeat(50));
    }

    @Test
    @DisplayName("보조 평면 문자(이모지)는 코드포인트 하나로 센다: 이름 이모지 50개 → 200 + DB 저장, 51개 → 400 violations.name")
    void supplementaryCharactersCountAsOne() throws Exception {
        String emoji = "\uD83D\uDE00";   // 😀 char 2개 = 코드포인트 1개
        String fifty = emoji.repeat(50);   // char 100 — @Size(max=50) 였다면 거절됐을 값
        mvc.perform(put(PATH).header(JwtAuthenticationFilter.HEADER, userToken).contentType(MediaType.APPLICATION_JSON)
                        .content(body(fifty, "010", "1", "x", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shippingAddress.name").value(fifty));
        assertThat(jdbc.queryForObject("SELECT default_ship_to_name FROM customers WHERE id = ?", String.class, customer.getId())).isEqualTo(fifty);   // utf8mb4 varchar(50) 에 들어간다

        mvc.perform(put(PATH).header(JwtAuthenticationFilter.HEADER, userToken).contentType(MediaType.APPLICATION_JSON)
                        .content(body(emoji.repeat(51), "010", "1", "x", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[?(@.field == \'name\')]").exists());
    }

    @Test
    @DisplayName("NFD 한글(자모 분리, macOS 붙여넣기 모양)은 NFC 로 정규화해 센다: 음절 50개 → 200 + DB CHAR_LENGTH 50, 51개 → 400")
    void nfdHangulIsNormalizedBeforeCounting() throws Exception {
        String nfc50 = "한국어이름".repeat(10);                                        // 코드포인트 50
        String nfd50 = java.text.Normalizer.normalize(nfc50, java.text.Normalizer.Form.NFD);
        assertThat(nfd50.codePointCount(0, nfd50.length())).isEqualTo(130);            // 정규화 전에는 130 — @Size/@CodePointSize 가 그대로 세면 거절됐을 값

        mvc.perform(put(PATH).header(JwtAuthenticationFilter.HEADER, userToken).contentType(MediaType.APPLICATION_JSON)
                        .content(body(nfd50, "010", "1", "x", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shippingAddress.name").value(nfc50));       // 응답도 NFC
        assertThat(jdbc.queryForObject("SELECT CHAR_LENGTH(default_ship_to_name) FROM customers WHERE id = ?", Integer.class, customer.getId())).isEqualTo(50);
        assertThat(jdbc.queryForObject("SELECT default_ship_to_name FROM customers WHERE id = ?", String.class, customer.getId())).isEqualTo(nfc50);

        String nfd51 = java.text.Normalizer.normalize(nfc50 + "한", java.text.Normalizer.Form.NFD);
        mvc.perform(put(PATH).header(JwtAuthenticationFilter.HEADER, userToken).contentType(MediaType.APPLICATION_JSON)
                        .content(body(nfd51, "010", "1", "x", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[?(@.field == \'name\')]").exists());
    }

    @Test
    @DisplayName("ADMIN 토큰은 GET·PUT 모두 403 FORBIDDEN 봉투(PUT 은 @Valid 본문보다 리졸버가 먼저), 토큰 없으면 401 UNAUTHENTICATED 봉투")
    void adminIs403AndAnonymousIs401() throws Exception {
        String admin = provider.create("admin", Role.ADMIN, UUID.randomUUID(), TokenType.ACCESS);
        mvc.perform(get(PATH).header(JwtAuthenticationFilter.HEADER, admin))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(put(PATH).header(JwtAuthenticationFilter.HEADER, admin).contentType(MediaType.APPLICATION_JSON).content(FULL))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(FULL))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("실측: DDL 의 ck_customer_default_address — 이름 한 칸만 넣는 부분 갱신은 MySQL 3819 로 거부된다 (서버가 다섯 칸을 한 덩어리로 쓰는 이유)")
    void partialWriteIsRejectedByCheckConstraint() {
        Throwable t = catchThrowable(() -> jdbc.update("UPDATE customers SET default_ship_to_name = ? WHERE id = ?", "홍길동", customer.getId()));

        // 실측: 3819 는 SQL state HY000 이라 스프링이 DataIntegrityViolationException 으로 번역하지 못하고 UncategorizedSQLException 으로 온다.
        // 그래서 CHECK 위반이 서비스까지 올라오면 봉투는 500 이다 — 부분 입력을 DB 전에 400 으로 막아야 하는 또 하나의 이유.
        assertThat(t).isInstanceOf(DataAccessException.class).isInstanceOf(UncategorizedSQLException.class);
        Throwable root = t;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertThat(root).isInstanceOf(SQLException.class);
        assertThat(((SQLException) root).getErrorCode()).isEqualTo(3819);
        assertThat(root.getMessage()).contains("ck_customer_default_address");

        // 비교 실측: 길이 초과(1406, SQLSTATE 22001)는 번역이 된다 — DataIntegrityViolationException. CHECK 만 번역 밖이다.
        Throwable tooLong = catchThrowable(() -> jdbc.update(
                "UPDATE customers SET default_ship_to_name = ?, default_ship_to_phone = ?, default_ship_to_postal_code = ?, default_ship_to_line1 = ? WHERE id = ?",
                "홍", "010", "1".repeat(11), "x", customer.getId()));
        assertThat(tooLong).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        Throwable tooLongRoot = tooLong;
        while (tooLongRoot.getCause() != null) {
            tooLongRoot = tooLongRoot.getCause();
        }
        assertThat(((SQLException) tooLongRoot).getErrorCode()).isEqualTo(1406);
    }
}
