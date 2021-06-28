package com.leaprnd.migrannotate;

import java.sql.SQLException;

import static java.lang.String.format;

public class FailedToMigrateException extends RuntimeException {

	private final long id;

	public FailedToMigrateException(long id, SQLException cause) {
		super(cause);
		this.id = id;
	}

	@Override
	public String getMessage() {
		return format("Failed to migrate @SchemaIdentifiter(%dL)", id);
	}

}
