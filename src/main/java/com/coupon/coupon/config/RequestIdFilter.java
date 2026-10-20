package com.coupon.coupon.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gateway(Traefik)가 붙인 요청 ID(X-Request-Id)를 로그에 남긴다.
 * MDC에 넣어 두면 이 요청을 처리하는 동안 찍히는 모든 로그 줄에 [요청 ID]가 붙는다 (logging.pattern.correlation).
 * → Traefik 접근 로그의 request_X-Request-Id와 같은 값으로 앱 로그를 찾을 수 있다.
 * Traefik을 거치지 않은 요청(로컬 실행, 테스트)이나 형식이 틀린 값이면 새로 만든다.
 * 보안 필터보다 먼저 실행해서 401·403 로그에도 붙게 한다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Request-Id";
	public static final String MDC_KEY = "requestId";
	private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{8,64}");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String id = request.getHeader(HEADER);
		if (id == null || !VALID.matcher(id).matches()) {
			id = UUID.randomUUID().toString();
		}
		MDC.put(MDC_KEY, id);
		response.setHeader(HEADER, id);
		try {
			chain.doFilter(request, response);
		} finally {
			MDC.remove(MDC_KEY);
		}
	}
}
