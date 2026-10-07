package com.geststock.permission;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.geststock.exception.ErrorCode;
import com.geststock.exception.InvalidRequestException;
import com.geststock.permission.dto.PermissionResponse;

/**
 * Catalogue des permissions (API publique du module {@code permission}). Le catalogue est
 * livré par les migrations et n'est pas modifiable par l'API : une permission n'a de sens
 * que si un endpoint la vérifie.
 */
@Service
@Transactional(readOnly = true)
public class PermissionService {

	private final PermissionRepository repository;

	PermissionService(PermissionRepository repository) {
		this.repository = repository;
	}

	public List<PermissionResponse> list() {
		return this.repository.findAll().stream()
			.sorted(Comparator.comparing(Permission::getCode))
			.map(PermissionService::toResponse)
			.toList();
	}

	public List<String> allCodes() {
		return this.repository.findAll().stream().map(Permission::getCode).sorted().toList();
	}

	/**
	 * Permissions correspondant aux codes demandés.
	 *
	 * @throws InvalidRequestException {@code UNKNOWN_PERMISSION} si un code n'existe pas
	 */
	public Set<Permission> findAllByCodes(Collection<String> codes) {
		Set<String> wanted = new LinkedHashSet<>(codes);
		List<Permission> found = this.repository.findAllByCodeIn(wanted);
		if (found.size() != wanted.size()) {
			Set<String> unknown = new TreeSet<>(wanted);
			found.forEach(permission -> unknown.remove(permission.getCode()));
			throw new InvalidRequestException(ErrorCode.UNKNOWN_PERMISSION,
					"Permission inconnue : " + String.join(", ", unknown) + ".");
		}
		return new LinkedHashSet<>(found);
	}

	static PermissionResponse toResponse(Permission permission) {
		String code = permission.getCode();
		int separator = code.indexOf(':');
		return new PermissionResponse(code, code.substring(0, separator), code.substring(separator + 1),
				permission.getDescription());
	}

}
