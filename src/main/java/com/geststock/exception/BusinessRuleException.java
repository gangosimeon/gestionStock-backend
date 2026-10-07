package com.geststock.exception;

import java.util.Map;

/** Règle de gestion non respectée (422), ex. stock insuffisant, plafond de crédit dépassé. */
public class BusinessRuleException extends ApiException {

	public BusinessRuleException(String message) {
		super(ErrorCode.BUSINESS_RULE_VIOLATION, message);
	}

	public BusinessRuleException(ErrorCode code, String message) {
		super(code, message);
	}

	public BusinessRuleException(ErrorCode code, String message, Map<String, Object> details) {
		super(code, message, details);
	}

}
