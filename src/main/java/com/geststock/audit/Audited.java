package com.geststock.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Point d'entrée sensible : s'il se termine en erreur (refus de droit, validation, règle
 * métier, conflit, erreur serveur), l'échec est tracé dans le journal ({@code FAILURE}, code
 * d'erreur et statut HTTP), au nom de l'utilisateur connecté.
 *
 * <p>La réussite, elle, est tracée par le service métier dans sa transaction (avec ses
 * détails et ses valeurs avant/après) : {@code action} et {@code entityType} doivent
 * reprendre les mêmes valeurs, pour que réussites et échecs se lisent ensemble.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Audited {

	/** Verbe tracé ({@code CREATE}, {@code CANCEL}…). */
	String action();

	/** Type d'objet, déclaré dans {@link AuditModule}. */
	String entityType();

	/** Variable de chemin portant l'identifiant de l'objet ({@code ""} : aucune). */
	String idParam() default "id";

	/** Module, quand il diffère de celui du type d'entité ({@code PASSWORD_CHANGE} : AUTH). */
	AuditModule[] module() default {};

}
