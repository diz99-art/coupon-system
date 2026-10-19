package com.coupon.coupon.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 부하 테스트에서 어느 서버가 응답했는지 세려고 응답 헤더에 서버 이름(컨테이너 호스트명)을 붙인다. */
@Component
@Profile("bench")
public class ServerIdHeaderFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Server-Id";

	private final String serverId = hostName();

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		response.setHeader(HEADER, serverId);
		chain.doFilter(request, response);
	}

	private static String hostName() {
		try {
			return InetAddress.getLocalHost().getHostName();
		} catch (UnknownHostException e) {
			return "unknown";
		}
	}
}
