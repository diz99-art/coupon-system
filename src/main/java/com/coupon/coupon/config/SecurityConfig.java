package com.coupon.coupon.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import jakarta.servlet.http.HttpServletResponse;

/**
 * 인증·권한. 인증 서버(platform-services의 auth)가 발급한 JWT를 받는 "리소스 서버"다.
 * - 개인키는 없다. 인증 서버의 공개키 목록(JWKS)으로 서명만 검증한다
 * - 쿠폰 조회는 공개, 생성·수정은 ADMIN, 발급과 내 발급 내역은 로그인 사용자
 */
@Configuration
public class SecurityConfig {

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
				// 세션·쿠키 없이 매 요청의 Authorization: Bearer 토큰으로 인증한다 → CSRF 방어가 필요 없다
				.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.httpBasic(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable)
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(HttpMethod.GET, "/coupons/**").permitAll()
						.requestMatchers(HttpMethod.POST, "/coupons").hasRole("ADMIN")
						.requestMatchers(HttpMethod.PUT, "/coupons/**").hasRole("ADMIN")
						.requestMatchers(HttpMethod.POST, "/coupons/*/issue").authenticated()
						.requestMatchers(HttpMethod.GET, "/users/me/coupons").authenticated()
						.requestMatchers(HttpMethod.GET, "/users/*/coupons").hasRole("ADMIN")
						// API 문서, 관리용 엔드포인트, 부하 비교 API(bench 프로필에만 있음, 운영에는 없어 404)
						.requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**", "/error", "/actuator/**", "/bench/**").permitAll()
						.anyRequest().authenticated())
				.oauth2ResourceServer(oauth -> oauth
						.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
						.authenticationEntryPoint((req, res, e) -> writeError(res, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다. 유효한 액세스 토큰을 보내세요."))
						.accessDeniedHandler((req, res, e) -> writeError(res, HttpStatus.FORBIDDEN, "FORBIDDEN", "권한이 없습니다.")))
				.exceptionHandling(e -> e
						.authenticationEntryPoint((req, res, ex) -> writeError(res, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다. 유효한 액세스 토큰을 보내세요."))
						.accessDeniedHandler((req, res, ex) -> writeError(res, HttpStatus.FORBIDDEN, "FORBIDDEN", "권한이 없습니다.")));
		return http.build();
	}

	/**
	 * JWKS 주소에서 공개키를 받아 검증한다. RS256만 받는다(alg를 none·HS256으로 바꾼 위조 거부).
	 * 서명 외에 발급자(iss), 대상(aud), 만료(exp)도 확인한다.
	 * 공개키는 캐시하고, 토큰의 kid가 캐시에 없으면 JWKS를 다시 받는다 → 인증 서버가 키를 바꿔도 따라간다
	 */
	@Bean
	public JwtDecoder jwtDecoder(@Value("${coupon.auth.jwk-set-uri}") String jwkSetUri,
			@Value("${coupon.auth.issuer}") String issuer, @Value("${coupon.auth.audience}") String audience) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).jwsAlgorithm(SignatureAlgorithm.RS256).build();
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
				JwtValidators.createDefaultWithIssuer(issuer),
				new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience))));
		return decoder;
	}

	/** 토큰의 roles(["USER"])를 권한 ROLE_USER로 바꾼다 → hasRole("ADMIN") 검사 */
	private static JwtAuthenticationConverter jwtAuthenticationConverter() {
		JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
		authorities.setAuthoritiesClaimName("roles");
		authorities.setAuthorityPrefix("ROLE_");
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(authorities);
		return converter;
	}

	/** 컨트롤러 앞에서 거절할 때도 공통 에러 응답 모양으로 (메시지가 고정 문자열이라 JSON을 직접 만든다) */
	private static void writeError(HttpServletResponse response, HttpStatus status, String code, String message) throws IOException {
		byte[] body = ("{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"errors\":[]}").getBytes(StandardCharsets.UTF_8);
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		if (status == HttpStatus.UNAUTHORIZED) {
			response.setHeader("WWW-Authenticate", "Bearer");
		}
		response.setContentLength(body.length);
		response.getOutputStream().write(body);
	}
}
