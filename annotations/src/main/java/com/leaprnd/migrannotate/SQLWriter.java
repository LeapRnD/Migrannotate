package com.leaprnd.migrannotate;

import static java.lang.String.format;

public final class SQLWriter {

	private final long id;

	private final StringBuilder prologue = new StringBuilder();
	private final StringBuilder sql = new StringBuilder();
	private final StringBuilder epilogue = new StringBuilder();

	SQLWriter(long id) {
		this.id = id;
	}

	long getId() {
		return id;
	}

	public void appendToPrologue(String format) {
		prologue.append(format);
	}

	public void appendToPrologue(String format, Object ... arguments) {
		prologue.append(format(format, arguments));
	}

	public void append(char character) {
		sql.append(character);
	}

	public void append(String format) {
		sql.append(format);
	}

	public void append(String format, Object ... arguments) {
		sql.append(format(format, arguments));
	}

	public void appendToEpilogue(String format) {
		epilogue.append(format);
	}

	public void appendToEpilogue(String format, Object ... arguments) {
		epilogue.append(format(format, arguments));
	}

	public String getPrologue() {
		return prologue.toString();
	}

	public String getSql() {
		return sql.toString();
	}

	public String getEpilogue() {
		return epilogue.toString();
	}

}