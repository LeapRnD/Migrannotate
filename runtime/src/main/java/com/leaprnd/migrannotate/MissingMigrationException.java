package com.leaprnd.migrannotate;

import static java.lang.String.format;

public class MissingMigrationException extends RuntimeException {

	private final String pathToClassFile;

	public MissingMigrationException(String pathToClassFile) {
		this.pathToClassFile = pathToClassFile;
	}

	@Override
	public String getMessage() {
		return format("Cannot find class listed in manifest: %s!", pathToClassFile);
	}

}
