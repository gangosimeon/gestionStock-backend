package com.geststock.integration.link;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Collection;

import jakarta.validation.Valid;
import jakarta.validation.Validator;

import org.springframework.core.Conventions;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/**
 * Corps JSON des routes d'intégration et des écrans de liaison (lot 2, étape G15), juste après leur lecture :
 * <ol>
 * <li>caractères de contrôle ({@link ControlCharacters}) dans chaque texte du corps (composantes {@code String} des
 * records, listes de textes, records imbriqués) : {@code 400 VALIDATION_FAILED} ;</li>
 * <li>validation {@code @Valid}, faite ici plutôt que par Spring MVC : l'exception de validation de Spring porte
 * chaque valeur refusée ({@code rejected value [...]}), que Spring journalise en DEBUG et en TRACE (résolution de
 * l'exception, arguments du gestionnaire d'erreur). Les mêmes erreurs (champ, message) sont levées sans la valeur
 * refusée : même réponse {@code 400 VALIDATION_FAILED}, par le même gestionnaire.</li>
 * </ol>
 * Un corps valide est encore validé par Spring MVC ensuite ({@code @Valid}), sans effet.
 */
@ControllerAdvice(assignableTypes = { IntegrationLinkController.class, LinkController.class, PriceProposalController.class, OnlineOrderController.class })
class IntegrationBodyAdvice extends RequestBodyAdviceAdapter {

	private final SpringValidatorAdapter validator;

	IntegrationBodyAdvice(Validator validator) {
		this.validator = new SpringValidatorAdapter(validator);
	}

	@Override
	public boolean supports(MethodParameter parameter, Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
		return parameter.getParameterType().isRecord();
	}

	@Override
	public Object afterBodyRead(Object body, HttpInputMessage inputMessage, MethodParameter parameter, Type targetType,
			Class<? extends HttpMessageConverter<?>> converterType) {
		check(body, null);
		if (parameter.hasParameterAnnotation(Valid.class)) {
			validate(body, parameter);
		}
		return body;
	}

	/** Validation du corps ; erreurs reprises sans la valeur refusée. */
	private void validate(Object body, MethodParameter parameter) {
		String name = Conventions.getVariableNameForParameter(parameter);
		BeanPropertyBindingResult found = new BeanPropertyBindingResult(body, name);
		this.validator.validate(body, found);
		if (!found.hasErrors()) {
			return;
		}
		BeanPropertyBindingResult masked = new BeanPropertyBindingResult(body, name);
		for (ObjectError error : found.getGlobalErrors()) {
			masked.addError(new ObjectError(name, error.getCodes(), error.getArguments(), error.getDefaultMessage()));
		}
		for (FieldError error : found.getFieldErrors()) {
			masked.addError(new FieldError(name, error.getField(), null, error.isBindingFailure(), error.getCodes(),
					error.getArguments(), error.getDefaultMessage()));
		}
		throw IntegrationBodyAdvice.<RuntimeException>unchecked(new MethodArgumentNotValidException(parameter, masked));
	}

	/**
	 * Lève l'exception vérifiée de Spring telle quelle, sans la déclarer : {@code afterBodyRead} ne déclare aucune
	 * exception, mais son appelant ({@code resolveArgument}) déclare {@code throws Exception}. Elle suit ainsi le chemin
	 * habituel jusqu'au gestionnaire de {@code MethodArgumentNotValidException} (même réponse).
	 */
	@SuppressWarnings("unchecked")
	private static <E extends Throwable> E unchecked(Throwable ex) throws E {
		throw (E) ex;
	}

	private static void check(Object value, RecordComponent component) {
		if (value instanceof String text) {
			if (ControlCharacters.present(text, component != null && component.isAnnotationPresent(ControlCharacters.FreeText.class))) {
				throw ControlCharacters.refused(component != null ? component.getName() : "corps");
			}
		}
		else if (value instanceof Collection<?> values) {
			values.forEach(element -> check(element, component));
		}
		else if (value instanceof Record record) {
			for (RecordComponent child : record.getClass().getRecordComponents()) {
				check(read(record, child), child);
			}
		}
	}

	private static Object read(Record record, RecordComponent component) {
		try {
			Method accessor = component.getAccessor();
			accessor.setAccessible(true);
			return accessor.invoke(record);
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("Composante illisible : " + component.getName(), ex);
		}
	}

}
