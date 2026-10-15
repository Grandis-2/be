package com.grandis.nova.catalog.web;

import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.web.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 상품 등록(POST /api/v1/admin/products) 본문 상한. WAF 의 본문 크기 규칙(8KB)이 이 경로에서 빠지므로(백엔드 상한을 채운 등록이 8KB 를 넘는다)
 * 앱이 대신 막는다 — 등록은 본문을 문자열로 통째로 읽고, Tomcat 의 form 본문 상한은 JSON 에 걸리지 않는다.
 * 넘으면 413 + 공통 봉투(VALIDATION_FAILED, 사진 업로드의 413 과 같다).
 *
 * Content-Length 가 있으면 읽기 전에 거른다. 없으면(chunked) 상한 + 1 바이트까지만 읽어 판정하고, 통과하면 읽은 바이트를 다시 내준다 —
 * 세는 스트림을 컨트롤러에 넘겨 읽는 중에 던지면 본문 읽기 실패(400)로 바뀌어 413 을 낼 수 없다.
 */
@Component
public class RegistrationBodyLimitFilter extends OncePerRequestFilter {

    /** 256KiB. 화면이 쓰는 최대 등록(색 5 × 5장 + 상세 30장)은 약 8KB 다. */
    public static final int MAX_BYTES = 256 * 1024;
    static final String PATH = "/api/v1/admin/products";

    private final ObjectMapper objectMapper;

    public RegistrationBodyLimitFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // MVC 가 컨트롤러를 고를 때처럼 퍼센트 인코딩을 푼 경로로 본다 — 원문 URI 와 비교하면 /api/v1/admin/%70roducts 가 이 필터를 빠져나가
        // 등록에 닿는다(RequestBodyLimitTest). 경로 매개변수(;x=1)는 보안 방화벽이 여기 오기 전에 400 으로 거절한다(실측)
        return !("POST".equals(request.getMethod()) && PATH.equals(UrlPathHelper.defaultInstance.getLookupPathForRequest(request)));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > MAX_BYTES) {
            tooLarge(response);
            return;
        }
        if (declared >= 0) {
            chain.doFilter(request, response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (body.length > MAX_BYTES) {
            tooLarge(response);
            return;
        }
        chain.doFilter(new BufferedBody(request, body), response);
    }

    private void tooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.fail(CommonErrorCode.VALIDATION_FAILED, "요청 본문이 %d 바이트를 넘습니다.".formatted(MAX_BYTES))));
    }

    /** 이미 읽은 본문을 다시 내주는 요청. */
    private static final class BufferedBody extends HttpServletRequestWrapper {

        private final byte[] body;

        BufferedBody(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() throws IOException {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    return in.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding() == null ? StandardCharsets.UTF_8.name() : getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), java.nio.charset.Charset.forName(encoding)));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
