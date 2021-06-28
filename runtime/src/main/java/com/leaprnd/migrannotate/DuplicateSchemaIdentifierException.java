package com.leaprnd.migrannotate;

import static java.lang.String.format;

public class DuplicateSchemaIdentifierException extends RuntimeException {

	private final String canonicalClassName;
	private final String conflictingCanonicalClassName;

	public DuplicateSchemaIdentifierException(String canonicalClassName, String conflictingCanonicalClassName) {
		this.canonicalClassName = canonicalClassName;
		this.conflictingCanonicalClassName = conflictingCanonicalClassName;
	}

	@Override
	public String getMessage() {
		return format("%s and %s have the same @SchemaIdentifier", canonicalClassName, conflictingCanonicalClassName);
	}

}
