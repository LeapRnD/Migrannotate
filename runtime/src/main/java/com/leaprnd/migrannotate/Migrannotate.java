package com.leaprnd.migrannotate;

import org.intellij.lang.annotations.Language;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.TreeMap;

import static com.leaprnd.migrannotate.ExecutionDirection.UNSTABLE;
import static com.leaprnd.migrannotate.Migrate.UNKNOWN_AT_COMPILE_TIME;
import static com.leaprnd.migrannotate.Migration.DEFAULT_GROUP;
import static com.leaprnd.migrannotate.Migration.EMPTY_CHECKSUM;
import static com.leaprnd.migrannotate.MigrationResult.ALREADY_UP_TO_DATE;
import static com.leaprnd.migrannotate.MigrationResult.FAILED_TO_LOCK;
import static com.leaprnd.migrannotate.MigrationResult.MIGRATED;
import static java.lang.ClassLoader.getSystemClassLoader;
import static java.lang.invoke.MethodHandles.Lookup.ClassOption.NESTMATE;
import static java.util.Comparator.comparingLong;

public class Migrannotate {

	public static final Comparator<Migration> MIGRATION_COMPARATOR = comparingLong(Migration::getId);

	@Language("SQL")
	private static final String SQL_TO_LOCK_AND_CREATE_TABLE_AND_SELECT_ALL_SCHEMA_ROWS = """
		SELECT pg_try_advisory_lock(7478093087527115071);
		CREATE TABLE IF NOT EXISTS "schema" (
			"id" BIGINT NOT NULL PRIMARY KEY,
			"checksum" BIGINT NOT NULL
		);
		SELECT "id", "checksum" FROM "schema";
		""";

	@Language("SQL")
	private static final String SQL_TO_INSERT_SCHEMA_ROW = """
		INSERT INTO "schema" VALUES (%d, %d);
		""";

	@Language("SQL")
	private static final String SQL_TO_UPDATE_SCHEMA_ROW = """
		UPDATE "schema" SET "checksum" = %d WHERE "id" = %d;
		""";

	@Language("SQL")
	private static final String SQL_TO_DELETE_SCHEMA_ROW = """
		DELETE FROM "schema" WHERE "id" = %d;
		""";

	@Language("SQL")
	private static final String SQL_TO_UNLOCK = """
		SELECT pg_advisory_unlock(7478093087527115071);
		""";

	private final String group;
	private final Connection connection;
	private final HashSet<Migration> extraMigrations = new HashSet<>();
	private final ExecutionDirection executionDirection;

	public Migrannotate(Connection connection) {
		this(DEFAULT_GROUP, connection);
	}

	public Migrannotate(String group, Connection connection) {
		this(group, connection, UNSTABLE);
	}

	public Migrannotate(Connection connection, ExecutionDirection executionDirection) {
		this(DEFAULT_GROUP, connection, executionDirection);
	}

	public Migrannotate(String group, Connection connection, ExecutionDirection executionDirection) {
		this.group = group;
		this.connection = connection;
		this.executionDirection = executionDirection;
	}

	public Migrannotate add(Migration extraMigration) {
		extraMigrations.add(extraMigration);
		return this;
	}

	public MigrationResult migrate() throws SQLException {
		final var oldAutoCommit = connection.getAutoCommit();
		connection.setAutoCommit(false);
		try {
			final var statement = connection.createStatement();
			try {
				statement.execute(SQL_TO_LOCK_AND_CREATE_TABLE_AND_SELECT_ALL_SCHEMA_ROWS);
				if (readWhetherLockFailedFrom(statement)) {
					return FAILED_TO_LOCK;
				}
				final var currentChecksumsById = readCurrentChecksumsByIdFrom(statement);
				final var writers = createSqlWriters(currentChecksumsById);
				if (writers.isEmpty()) {
					return ALREADY_UP_TO_DATE;
				}
				for (final var writer : writers) {
					try {
						statement.execute(writer.getPrologue());
					} catch (SQLException exception) {
						throw new FailedToMigrateException(writer.getId(), exception);
					}
				}
				connection.commit();
				for (final var writer : writers) {
					try {
						statement.execute(writer.getSql());
					} catch (SQLException exception) {
						throw new FailedToMigrateException(writer.getId(), exception);
					}
				}
				connection.commit();
				for (final var writer : writers) {
					try {
						statement.execute(writer.getEpilogue());
					} catch (SQLException exception) {
						throw new FailedToMigrateException(writer.getId(), exception);
					}
				}
				connection.commit();
				return MIGRATED;
			} catch (Throwable exception) {
				connection.rollback();
				throw exception;
			} finally {
				try {
					statement.execute(SQL_TO_UNLOCK);
				} finally {
					statement.close();
				}
			}
		} finally {
			connection.setAutoCommit(oldAutoCommit);
		}
	}

	private boolean readWhetherLockFailedFrom(Statement statement) throws SQLException {
		try (final var results = statement.getResultSet()) {
			if (results.next() && results.getBoolean(1)) {
				return false;
			}
		}
		return true;
	}

	private HashMap<Long, Long> readCurrentChecksumsByIdFrom(Statement statement) throws SQLException {
		final var currentChecksumsById = new HashMap<Long, Long>();
		while (true) {
			if (statement.getMoreResults()) {
				try (final var results = statement.getResultSet()) {
					while (results.next()) {
						final var id = results.getLong(1);
						final var currentChecksum = results.getLong(2);
						currentChecksumsById.put(id, currentChecksum);
					}
				}
			} else if (statement.getUpdateCount() < 0) {
				break;
			}
		}
		return currentChecksumsById;
	}

	private Collection<SQLWriter> createSqlWriters(Map<Long, Long> currentChecksums) {
		final var lookup = MethodHandles.lookup();
		final var classLoader = getSystemClassLoader();
		try {
			final var canonicalClassNamesById = new HashMap<Long, String>();
			final Map<Migration, SQLWriter> unordered = switch (executionDirection) {
				case UNSTABLE -> new HashMap<>();
				case FORWARD -> new TreeMap<>(MIGRATION_COMPARATOR);
				case BACKWARD -> new TreeMap<>(MIGRATION_COMPARATOR.reversed());
			};
			for (final var extraMigration : extraMigrations) {
				final var id = extraMigration.getId();
				final var canonicalClassName = extraMigration.getClass().getCanonicalName();
				canonicalClassNamesById.put(id, canonicalClassName);
				final var latestChecksum = extraMigration.getLatestChecksum();
				final var currentChecksum = currentChecksums.getOrDefault(id, EMPTY_CHECKSUM);
				if (currentChecksum == latestChecksum) {
					continue;
				}
				unordered.put(extraMigration, createSqlWriter(extraMigration, currentChecksum, latestChecksum));
			}
			final var resources = classLoader.getResources(group + ".migrannotate");
			while (resources.hasMoreElements()) {
				final var resource = resources.nextElement();
				try (final var inputStream = resource.openStream()) {
					try (final var dataInputStream = new DataInputStream(inputStream)) {
						while (true) {
							final var id = dataInputStream.readLong();
							var latestChecksum = dataInputStream.readLong();
							final var pathToClassFile = dataInputStream.readUTF();
							final var canonicalClassName = pathToClassFile.replaceAll("/", ".");
							final var conflictingCanonicalClassName = canonicalClassNamesById.put(id, canonicalClassName);
							if (conflictingCanonicalClassName != null) {
								throw new DuplicateSchemaIdentifierException(canonicalClassName, conflictingCanonicalClassName);
							}
							final var currentChecksum = currentChecksums.getOrDefault(id, EMPTY_CHECKSUM);
							if (latestChecksum != UNKNOWN_AT_COMPILE_TIME && currentChecksum == latestChecksum) {
								continue;
							}
							final byte[] classBytes;
							try (final var classInputStream = classLoader.getResourceAsStream(pathToClassFile)) {
								if (classInputStream == null) {
									throw new MissingMigrationException(pathToClassFile);
								}
								classBytes = classInputStream.readAllBytes();
							}
							final var object = lookup
								.defineHiddenClass(classBytes, true, NESTMATE)
								.lookupClass()
								.getConstructor()
								.newInstance();
							if (object instanceof final Migration migration) {
								if (latestChecksum == UNKNOWN_AT_COMPILE_TIME) {
									latestChecksum = migration.getLatestChecksum();
								}
								unordered.put(migration, createSqlWriter(migration, currentChecksum, latestChecksum));
							} else {
								throw new IllegalStateException();
							}
						}
					} catch (EOFException exception) {
						continue;
					}
				}
			}
			return getValuesInOrder(unordered);
		} catch (IOException | ReflectiveOperationException exception) {
			throw new RuntimeException(exception);
		}
	}

	private SQLWriter createSqlWriter(Migration migration, long currentChecksum, long latestChecksum) {
		final var id = migration.getId();
		final var writer = new SQLWriter(id);
		migration.migrate(currentChecksum, writer);
		if (currentChecksum == EMPTY_CHECKSUM) {
			writer.append(SQL_TO_INSERT_SCHEMA_ROW.formatted(id, latestChecksum));
		} else if (latestChecksum == EMPTY_CHECKSUM) {
			writer.append(SQL_TO_DELETE_SCHEMA_ROW.formatted(id));
		} else {
			writer.append(SQL_TO_UPDATE_SCHEMA_ROW.formatted(latestChecksum, id));
		}
		return writer;
	}

	private <X> Collection<X> getValuesInOrder(Map<Migration, X> unordered) {
		final var ordered = new LinkedHashSet<X>();
		while (true) {
			final var iterator = unordered.entrySet().iterator();
			if (iterator.hasNext()) {
				boolean unchanged = true;
				FIND_INDEPENDENT_MIGRATIONS: do {
					final var entry = iterator.next();
					final var migration = entry.getKey();
					for (final var otherMigration : unordered.keySet()) {
						if (migration == otherMigration) {
							continue;
						}
						if (migration.isDependentOn(otherMigration)) {
							continue FIND_INDEPENDENT_MIGRATIONS;
						}
					}
					final var value = entry.getValue();
					iterator.remove();
					ordered.add(value);
					unchanged = false;
				} while (iterator.hasNext());
				if (unchanged) {
					throw new IllegalStateException();
				}
			} else {
				return ordered;
			}
		}
	}

}