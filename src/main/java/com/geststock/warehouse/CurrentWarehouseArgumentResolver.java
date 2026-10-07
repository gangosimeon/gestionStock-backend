package com.geststock.warehouse;

import java.util.List;

import jakarta.servlet.http.HttpServletResponse;

import org.springdoc.core.utils.SpringDocUtils;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Résout les paramètres {@link CurrentWarehouse}. Le dépôt retenu est renvoyé dans l'en-tête
 * de réponse {@code X-Warehouse-Id} : le client sait où l'opération s'est faite, y compris
 * quand son choix a été remplacé par son dépôt par défaut.
 */
@Configuration(proxyBeanMethods = false)
class CurrentWarehouseArgumentResolver implements WebMvcConfigurer, HandlerMethodArgumentResolver {

	static final String HEADER = "X-Warehouse-Id";

	static {
		// Paramètre résolu par le serveur : absent du contrat OpenAPI (seul l'en-tête compte).
		SpringDocUtils.getConfig().addAnnotationsToIgnore(CurrentWarehouse.class);
	}

	/** Différé : les tranches de test MVC n'ont pas la couche persistance. */
	private final ObjectProvider<WarehouseAccess> access;

	CurrentWarehouseArgumentResolver(ObjectProvider<WarehouseAccess> access) {
		this.access = access;
	}

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(this);
	}

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.hasParameterAnnotation(CurrentWarehouse.class)
				&& WarehouseContext.class.equals(parameter.getParameterType());
	}

	@Override
	public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
		WarehouseContext context = this.access.getObject().resolve(webRequest.getHeader(HEADER));
		HttpServletResponse response = webRequest.getNativeResponse(HttpServletResponse.class);
		if (response != null) {
			response.setHeader(HEADER, context.id().toString());
		}
		return context;
	}

}
