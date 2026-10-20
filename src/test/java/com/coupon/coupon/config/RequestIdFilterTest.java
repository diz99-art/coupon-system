package com.coupon.coupon.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

	private final RequestIdFilter filter = new RequestIdFilter();

	private String run(String header, MockHttpServletResponse response) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		if (header != null) {
			request.addHeader(RequestIdFilter.HEADER, header);
		}
		AtomicReference<String> seen = new AtomicReference<>();
		filter.doFilter(request, response, (req, res) -> seen.set(MDC.get(RequestIdFilter.MDC_KEY)));
		return seen.get();
	}

	@Test
	void keepsValidIdFromGateway() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		assertThat(run("c8637723-e868-4a81-8a3c-4e0d8924d7c1", response)).isEqualTo("c8637723-e868-4a81-8a3c-4e0d8924d7c1");
		assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("c8637723-e868-4a81-8a3c-4e0d8924d7c1");
		assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
	}

	@Test
	void replacesMissingOrInvalidId() throws Exception {
		assertThat(run(null, new MockHttpServletResponse())).hasSize(36);
		assertThat(run("bad id<script>", new MockHttpServletResponse())).hasSize(36).doesNotContain("<");
	}
}
