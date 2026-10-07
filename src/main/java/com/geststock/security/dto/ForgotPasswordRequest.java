package com.geststock.security.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/** Demande de réinitialisation (contrat du front : {@code {email}}). */
@Schema(name = "ForgotPasswordRequest")
public record ForgotPasswordRequest(@NotBlank @Email @Size(max = 254) String email) {
}
