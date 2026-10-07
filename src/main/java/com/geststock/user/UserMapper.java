package com.geststock.user;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.geststock.common.jpa.BaseEntity;
import com.geststock.role.Role;
import com.geststock.user.dto.UserResponse;

/**
 * Conversion entité → DTO. Appelé à l'intérieur d'une transaction (collections paresseuses
 * chargées par lots). Ne lit jamais le haché du mot de passe, seulement sa présence.
 */
@Component
class UserMapper {

	UserResponse toResponse(AppUser user) {
		List<String> roles = user.getRoles().stream().map(Role::getCode).sorted().toList();
		List<UUID> warehouseIds = user.getWarehouses().stream()
			.map(BaseEntity::getId)
			.sorted(Comparator.naturalOrder())
			.toList();
		return new UserResponse(
				user.getId(),
				user.getUsername(),
				user.getFirstName(),
				user.getLastName(),
				user.getFullName(),
				user.getEmail(),
				user.getPhone(),
				roles,
				legacyRole(roles),
				user.isActive(),
				user.hasPassword(),
				user.isMustChangePassword(),
				user.getDefaultWarehouse() != null ? user.getDefaultWarehouse().getId() : null,
				warehouseIds,
				user.getLastLoginAt(),
				user.getCreatedAt(),
				user.getUpdatedAt());
	}

	/**
	 * Rôle d'affichage attendu par l'en-tête du front (modèle historique ADMIN / MANAGER /
	 * EMPLOYEE) : le plus élevé des rôles métier.
	 */
	static String legacyRole(List<String> roles) {
		if (roles.contains("ADMIN")) {
			return "ADMIN";
		}
		if (roles.contains("GESTIONNAIRE")) {
			return "MANAGER";
		}
		return "EMPLOYEE";
	}

}
