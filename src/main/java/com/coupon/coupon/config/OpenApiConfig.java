package com.coupon.coupon.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;

/** Swagger 화면 오른쪽 위 Authorize에 인증 서버(/auth/login)에서 받은 액세스 토큰을 넣으면 발급·생성 API를 시험할 수 있다 */
@Configuration
public class OpenApiConfig {

	@Bean
	public OpenAPI openApi() {
		return new OpenAPI().components(new Components().addSecuritySchemes("bearer",
				new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
	}
}
