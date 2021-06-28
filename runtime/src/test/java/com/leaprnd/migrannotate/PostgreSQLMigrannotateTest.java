package com.leaprnd.migrannotate;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.sql.DriverManager;
import java.sql.SQLException;

import static org.testcontainers.jdbc.ContainerDatabaseDriver.killContainer;

public class PostgreSQLMigrannotateTest extends MigrannotateTest {

	private static final String URL = "jdbc:tc:postgresql:13.3:///migrannotate";

	@BeforeAll
	public static void startDatabase() throws SQLException {
		connection = DriverManager.getConnection(URL);
	}

	@AfterAll
	public static void stopDatabase() {
		killContainer(URL);
	}

}
