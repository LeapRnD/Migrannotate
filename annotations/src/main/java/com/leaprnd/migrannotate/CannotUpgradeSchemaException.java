package com.leaprnd.migrannotate;

import static java.lang.String.format;

public class CannotUpgradeSchemaException extends RuntimeException {

	private final String canonicalNameOfAnnotatedClass;
	private final long currentChecksum;
	private final long latestChecksum;

	public CannotUpgradeSchemaException(String canonicalNameOfAnnotatedClass, long currentChecksum, long latestChecksum) {
		this.canonicalNameOfAnnotatedClass = canonicalNameOfAnnotatedClass;
		this.currentChecksum = currentChecksum;
		this.latestChecksum = latestChecksum;
	}

	@Override
	public String getMessage() {
		return format(
			"There is no @SchemaUpgrade(from = %dL, to = %dL, sql = ...) annotation on %s!",
			currentChecksum,
			latestChecksum,
			canonicalNameOfAnnotatedClass
		);
	}

}
