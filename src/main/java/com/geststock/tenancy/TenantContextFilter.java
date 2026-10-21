package com.geststock.tenancy;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Premier filtre de la chaîne : garantit qu'aucune entreprise ne survit à une requête (threads
 * réutilisés par Tomcat). Le contexte est vidé avant et après, y compris en cas d'erreur.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantContextFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		TenantContext.clear();
		try {
			chain.doFilter(request, response);
		}
		finally {
			TenantContext.clear();
		}
	}

}
