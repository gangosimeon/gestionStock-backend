package com.geststock.report;

import java.time.ZoneId;

import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Paramètres du tableau de bord ({@code geststock.dashboard.*}).
 *
 * @param timeZone fuseau de l'entreprise : définit « aujourd'hui » et le découpage par jour
 *                 (Abidjan = UTC, sans heure d'été)
 */
@Validated
@ConfigurationProperties("geststock.dashboard")
public record DashboardProperties(@NotNull @DefaultValue("Africa/Abidjan") ZoneId timeZone) {
}
