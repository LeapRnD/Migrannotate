package com.leaprnd.migrannotate;

import java.util.regex.Pattern;

public interface Migration {

	static String enquoteLiteral(String value) {
		return "'" + value.replace("'", "''") + "'";
	}

	static String enquoteIdentifier(String identifier) {
		int len = identifier.length();
		if (len < 1 || len > 128) {
			throw new IllegalArgumentException("Invalid name");
		}
		if (Pattern.compile("[\\p{Alpha}][\\p{Alnum}_]*").matcher(identifier).matches()) {
			return "\"" + identifier + "\"";
		}
		if (identifier.matches("^\".+\"$")) {
			identifier = identifier.substring(1, len - 1);
		}
		if (Pattern.compile("[^\u0000\"]+").matcher(identifier).matches()) {
			return "\"" + identifier + "\"";
		} else {
			throw new IllegalArgumentException("Invalid name");
		}
	}

	String DEFAULT_GROUP = "default";
	long EMPTY_CHECKSUM = 0L;

	long getId();
	long migrate(long currentChecksum, StringBuilder sql);

	default boolean isDependentOn(Migration other) {
		return false;
	}

}