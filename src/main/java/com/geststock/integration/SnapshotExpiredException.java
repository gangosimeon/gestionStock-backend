package com.geststock.integration;

import com.geststock.exception.ApiException;
import com.geststock.exception.ErrorCode;

/** {@code pageToken} émis il y a plus de 24 heures : 410 {@code SNAPSHOT_EXPIRED}, recommencer sans pageToken. */
public class SnapshotExpiredException extends ApiException {

	public SnapshotExpiredException() {
		super(ErrorCode.SNAPSHOT_EXPIRED, null);
	}

}
