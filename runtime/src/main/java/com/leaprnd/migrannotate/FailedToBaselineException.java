package com.leaprnd.migrannotate;

import java.sql.SQLException;

public class FailedToBaselineException extends RuntimeException {
	public FailedToBaselineException(SQLException cause) {
		super(cause);
	}
}