package com.leaprnd.migrannotate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.leaprnd.migrannotate.MigrationResult.ALREADY_UP_TO_DATE;
import static com.leaprnd.migrannotate.MigrationResult.MIGRATED;
import static com.leaprnd.migrannotate.Person.PERSON_GROUP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public abstract class MigrannotateTest {

	protected static final Lock LOCK = new ReentrantLock();

	static {
		try {
			Class.forName("org.testcontainers.jdbc.ContainerDatabaseDriver");
		} catch (ClassNotFoundException exception) {
			fail(exception);
		}
	}

	protected static Connection connection;

	@BeforeEach
	public void lock() {
		LOCK.lock();
	}

	@Test
	public void testMigrateSuccessWhenDatabaseIsEmpty() throws Exception {
		final var migrannotate = new Migrannotate(connection);
		assertEquals(MIGRATED, migrannotate.migrate());
		verifyDefaultSchema();
	}

	@Test
	public void testMigrateSuccessWhenSchemaTableIsEmpty() throws Exception {
		try (final var statement = connection.createStatement()) {
			statement.executeUpdate("""
				CREATE TABLE "schema" (
					"id" BIGINT NOT NULL PRIMARY KEY,
					"checksum" BIGINT NOT NULL
				);
				""");
		}
		final var migrannotate = new Migrannotate(connection);
		assertEquals(MIGRATED, migrannotate.migrate());
		verifyDefaultSchema();
	}

	@Test
	public void testMigrateSuccessWhenSchemaIsBehindByOneUpgrade() throws Exception {
		try (final var statement = connection.createStatement()) {
			statement.executeUpdate("""
				CREATE TABLE "knight" (
					"id" BIGINT NOT NULL GENERATED ALWAYS AS IDENTITY,
					"name" VARCHAR NOT NULL,
					CONSTRAINT "pkKnight" PRIMARY KEY ("id")
				);
				CREATE TABLE "schema" (
					"id" BIGINT NOT NULL PRIMARY KEY,
					"checksum" BIGINT NOT NULL
				);
				INSERT INTO "schema" ("id", "checksum") VALUES (6067387809931810870, 1759279540);
				""");
		}
		final var migrannotate = new Migrannotate(connection);
		assertEquals(MIGRATED, migrannotate.migrate());
		verifyDefaultSchema();
	}

	@Test
	public void testMigrateSuccessWhenSchemaIsBehindByTwoUpgrades() throws Exception {
		try (final var statement = connection.createStatement()) {
			statement.executeUpdate("""
				CREATE TABLE "knight" (
					"id" BIGINT NOT NULL GENERATED ALWAYS AS IDENTITY,
					CONSTRAINT "pkKnight" PRIMARY KEY ("id")
				);
				CREATE TABLE "schema" (
					"id" BIGINT NOT NULL PRIMARY KEY,
					"checksum" BIGINT NOT NULL
				);
				INSERT INTO "schema" ("id", "checksum") VALUES (6067387809931810870, 1305369197);
				""");
		}
		final var migrannotate = new Migrannotate(connection);
		assertEquals(MIGRATED, migrannotate.migrate());
		verifyDefaultSchema();
	}

	@Test
	public void testMigrateSuccessWhenRepeatableSchemaIsOutOfDate() throws Exception {
		try (final var statement = connection.createStatement()) {
			statement.executeUpdate("""
				CREATE TABLE "sauce" (
					"id" BIGINT NOT NULL GENERATED ALWAYS AS IDENTITY PRIMARY KEY
				);
				CREATE TABLE "schema" (
					"id" BIGINT NOT NULL PRIMARY KEY,
					"checksum" BIGINT NOT NULL
				);
				INSERT INTO "schema" ("id", "checksum") VALUES (-3799711284230107153, 1156034694565169423);
				""");
		}
		final var migrannotate = new Migrannotate(connection);
		assertEquals(MIGRATED, migrannotate.migrate());
		verifyDefaultSchema();
	}

	@Test
	public void testMigrateSuccessAllSchemasAreUpToDate() throws Exception {
		try (final var statement = connection.createStatement()) {
			statement.executeUpdate("""
				CREATE TABLE "apple" (
					"id" BIGINT NOT NULL GENERATED ALWAYS AS IDENTITY,
					"name" VARCHAR NOT NULL,
					CONSTRAINT "pkApple" PRIMARY KEY ("id")
				);
				CREATE TABLE "sauce" (
					"id" BIGINT NOT NULL GENERATED ALWAYS AS IDENTITY,
					"name" VARCHAR NOT NULL,
					CONSTRAINT "pkSauce" PRIMARY KEY ("id")
				);
				CREATE TABLE "knight" (
					"id" BIGINT NOT NULL GENERATED ALWAYS AS IDENTITY,
					"name" VARCHAR NOT NULL,
					"favoriteApple" BIGINT NOT NULL,
					"favoriteSauce" BIGINT NOT NULL,
					CONSTRAINT "pkKnight" PRIMARY KEY ("id"),
					CONSTRAINT "fkKnightFavoriteApple" FOREIGN KEY ("favoriteApple") REFERENCES "apple" ("id") ON UPDATE CASCADE ON DELETE CASCADE,
					CONSTRAINT "fkKnightFavoriteSauce" FOREIGN KEY ("favoriteSauce") REFERENCES "sauce" ("id") ON UPDATE CASCADE ON DELETE CASCADE
				);
				CREATE TABLE "jedi" (
					"knight" BIGINT NOT NULL,
					"midiChlorianDensity" FLOAT NOT NULL,
					CONSTRAINT "pkJedi" PRIMARY KEY ("knight"),
					CONSTRAINT "fkJediKnight" FOREIGN KEY ("knight") REFERENCES "knight" ("id") ON UPDATE CASCADE ON DELETE CASCADE
				);
				CREATE TABLE "schema" (
					"id" BIGINT NOT NULL PRIMARY KEY,
					"checksum" BIGINT NOT NULL
				);
				INSERT INTO "schema" ("id", "checksum") VALUES
					(-8388116365589044375, 4138941721),
					(936908912345077096, -185220752402808832),
					(6067387809931810870, 2612757535),
					(5399356631421331000, 2142535672);
				""");
		}
		final var migrannotate = new Migrannotate(connection);
		assertEquals(ALREADY_UP_TO_DATE, migrannotate.migrate());
		verifyDefaultSchema();
	}

	@Test
	public void testMigrationFailureWhenSchemaCannotBeUpgraded() throws Exception {
		try (final var statement = connection.createStatement()) {
			statement.executeUpdate("""
				CREATE TABLE "apple" (
					"id" BIGINT NOT NULL GENERATED ALWAYS AS IDENTITY,
					CONSTRAINT "pkApple" PRIMARY KEY ("id")
				);
				CREATE TABLE "schema" (
					"id" BIGINT NOT NULL PRIMARY KEY,
					"checksum" BIGINT NOT NULL
				);
				INSERT INTO "schema" ("id", "checksum") VALUES (-8388116365589044375, 8648803050833897717);
				""");
		}
		final var migrannotate = new Migrannotate(connection);
		assertThrows(CannotUpgradeSchemaException.class, migrannotate::migrate);
	}

	@Test
	public void testMigrationFailureWhenUpgradeIsBroken() throws Exception {
		try (final var statement = connection.createStatement()) {
			statement.executeUpdate("""
				CREATE TABLE "knight" (
					"id" BIGINT NOT NULL GENERATED ALWAYS AS IDENTITY,
					CONSTRAINT "pkKnight" PRIMARY KEY ("id")
				);
				CREATE TABLE "jedi" (
					"knight" BIGINT NOT NULL,
					CONSTRAINT "pkJedi" PRIMARY KEY ("knight"),
					CONSTRAINT "fkJediKnight" FOREIGN KEY ("knight") REFERENCES "knight" ("id") ON UPDATE CASCADE ON DELETE CASCADE
				);
				CREATE TABLE "schema" (
					"id" BIGINT NOT NULL PRIMARY KEY,
					"checksum" BIGINT NOT NULL
				);
				
				INSERT INTO "schema" ("id", "checksum") VALUES
					(6067387809931810870, 1305369197),
					(5399356631421331000, 242734274);
				""");
		}
		final var migrannotate = new Migrannotate(connection);
		assertThrows(FailedToMigrateException.class, migrannotate::migrate);
	}

	private void verifyDefaultSchema() throws Exception {
		final var sql = """
			WITH A AS (
				INSERT INTO "apple" (
					"name"
				) VALUES (
					'Granny Smith'
				) RETURNING "id"
			), S AS (
				INSERT INTO "sauce" (
					"name"
				) VALUES (
					'Ketchup'
				) RETURNING "id"
			), K AS (
				INSERT INTO "knight" (
					"name",
					"favoriteApple",
					"favoriteSauce"
				) SELECT
					'Anakin Skywalker',
					A."id",
					S."id"
				FROM
					A CROSS JOIN S
				RETURNING
					"id"
			) INSERT INTO "jedi" (
				"knight",
				"midiChlorianDensity"
			) SELECT
				K."id",
				25000
			FROM
				K
			RETURNING
				"knight";
			""";
		try (final var statement = connection.prepareStatement(sql)) {
			try (final var results = statement.executeQuery()) {
				assertTrue(results.next());
				assertEquals(1, results.getLong("knight"));
				assertFalse(results.next());
			}
		}
		verifyTableDoesNotExist("person");
	}

	@Test
	public void testMigrationCustomGroupSuccessWithEnumsWhenDatabaseIsEmpty() throws Exception {
		final var migrannotate = new Migrannotate(PERSON_GROUP, connection);
		migrannotate.migrate();
		verifyPersonSchema();
	}

	@Test
	public void testMigrationCustomGroupSuccessWhenEnumAlreadyExists() throws Exception {
		try (final var statement = connection.createStatement()) {
			statement.executeUpdate("""
				CREATE TYPE "color" AS ENUM ();
				""");
		}
		final var migrannotate = new Migrannotate(PERSON_GROUP, connection);
		migrannotate.migrate();
		verifyPersonSchema();
	}

	@Test
	public void testMigrationCustomGroupSuccessWhenEnumValueAdded() throws Exception {
		try (final var statement = connection.createStatement()) {
			statement.executeUpdate("""

					CREATE TYPE "color" AS ENUM (
						'RED',
						'BLUE',
						'GREEN'
					);
										
					CREATE TYPE "handedness" AS ENUM (
						'LEFT_HANDED',
						'RIGHT_HANDED'
					);
										
					CREATE TABLE "person" (
						"id" BIGINT NOT NULL GENERATED ALWAYS AS IDENTITY,
						"favoriteColor" "color" NOT NULL,
						"handedness" "handedness" NOT NULL,
						CONSTRAINT "pkPerson" PRIMARY KEY ("id")
					);
										
					CREATE TABLE "schema" (
						"id" BIGINT NOT NULL PRIMARY KEY,
						"checksum" BIGINT NOT NULL
					);
										
					INSERT INTO "schema" ("id", "checksum") VALUES (936908912751334464, 7533965508328745674);

					""");
		}
		final var migrannotate = new Migrannotate(PERSON_GROUP, connection);
		migrannotate.migrate();
		verifyPersonSchema();
	}

	@Test
	public void testMigrationCustomGroupSuccessWhenUpgraded() throws Exception {
		try (final var statement = connection.createStatement()) {
			statement.executeUpdate("""

					CREATE TYPE "color" AS ENUM (
						'RED',
						'BLUE',
						'GREEN'
					);
					
					CREATE TABLE "person" (
						"id" BIGINT NOT NULL GENERATED ALWAYS AS IDENTITY,
						"favoriteColor" "color" NOT NULL,
						CONSTRAINT "pkPerson" PRIMARY KEY ("id")
					);
					
					CREATE TABLE "schema" (
						"id" BIGINT NOT NULL PRIMARY KEY,
						"checksum" BIGINT NOT NULL
					);
					
					INSERT INTO "schema" ("id", "checksum") VALUES (936908912751334464, 6282829462332680450);

					""");
		}
		final var migrannotate = new Migrannotate(PERSON_GROUP, connection);
		migrannotate.migrate();
		verifyPersonSchema();
	}

	private void verifyPersonSchema() throws Exception {
		final var sql = """
			INSERT INTO "person" (
				"favoriteColor",
				"handedness"
			) VALUES (
				'BLUE'::"color",
				'RIGHT_HANDED'::"handedness"
			);
			""";
		try (final var statement = connection.prepareStatement(sql)) {
			assertEquals(1, statement.executeUpdate());
		}
		verifyTableDoesNotExist("apple");
	}

	private void verifyTableDoesNotExist(String tableName) throws Exception {
		final var sql = """
			SELECT TRUE FROM
				information_schema.tables
			WHERE
				table_schema = 'public' AND
				table_name = ?;
			""";
		try (final var statement = connection.prepareStatement(sql)) {
			statement.setString(1, tableName);
			try (final var results = statement.executeQuery()) {
				assertFalse(results.next());
			}
		}
	}

	@AfterEach
	public void cleanUp() throws Exception {
		try (final var statement = connection.createStatement()) {
			statement.executeUpdate("""
				DROP SCHEMA public CASCADE;
				CREATE SCHEMA public;
				""");
		}
	}

	@AfterEach
	public void unlock() {
		LOCK.unlock();
	}

}
