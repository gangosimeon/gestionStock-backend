package com.geststock.common.web;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class TraceIdFilterTest {

	private final TraceIdFilter filter = new TraceIdFilter();

	@Test
	void generatesTraceIdAndExposesItDuringTheRequestOnly() throws Exception {
		AtomicReference<String> seenInMdc = new AtomicReference<>();
		MockHttpServletResponse response = new MockHttpServletResponse();

		this.filter.doFilter(new MockHttpServletRequest(), response,
				new MockFilterChain() {
					@Override
					public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
						seenInMdc.set(MDC.get(TraceIdFilter.MDC_KEY));
					}
				});

		String header = response.getHeader(TraceIdFilter.HEADER);
		assertThat(header).matches("[0-9a-f]{32}");
		assertThat(seenInMdc.get()).isEqualTo(header);
		assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
	}

	@Test
	void keepsWellFormedIncomingTraceId() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader(TraceIdFilter.HEADER, "abc-12345.XYZ");
		MockHttpServletResponse response = new MockHttpServletResponse();

		this.filter.doFilter(request, response, new MockFilterChain());

		assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo("abc-12345.XYZ");
	}

	@Test
	void replacesMalformedIncomingTraceId() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader(TraceIdFilter.HEADER, "<script>alert(1)</script>");
		MockHttpServletResponse response = new MockHttpServletResponse();

		this.filter.doFilter(request, response, new MockFilterChain());

		assertThat(response.getHeader(TraceIdFilter.HEADER)).matches("[0-9a-f]{32}");
	}

}
