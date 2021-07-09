package com.leaprnd.migrannotate;

import org.intellij.lang.annotations.Language;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

import static com.leaprnd.migrannotate.DependencyStrategy.STABLE;
import static com.leaprnd.migrannotate.Migration.DEFAULT_GROUP;
import static com.leaprnd.migrannotate.Migration.EMPTY_CHECKSUM;
import static com.leaprnd.migrannotate.MigrationResult.ALREADY_UP_TO_DATE;
import static com.leaprnd.migrannotate.MigrationResult.FAILED_TO_LOCK;
import static com.leaprnd.migrannotate.MigrationResult.MIGRATED;
import static java.lang.ClassLoader.getSystemClassLoader;
import static java.lang.invoke.MethodHandles.Lookup.ClassOption.NESTMATE;
import static java.util.Collections.emptyMap;
import static java.util.Comparator.comparingLong;

public class Migrannotate {

	public static final Comparator<Migration> MIGRATION_COMPARATOR = comparingLong(Migration::getId);

	@Language("SQL")
	private static final String SQL_TO_LOCK = """
		SELECT pg_try_advisory_lock(7478093087527115071);
		""";

	@Language("SQL")
	private static final String SQL_TO_CREATE_SCHEMA_TABLE = """
		CREATE TABLE IF NOT EXISTS "schema" (
			"id" BIGINT NOT NULL PRIMARY KEY,
			"checksum" BIGINT NOT NULL
		);
		""";

	@Language("SQL")
	private static final String SQL_TO_TRUNCATE_SCHEMA_TABLE = """
		TRUNCATE "schema";
		""";

	@Language("SQL")
	private static final String SQL_TO_SELECT_ALL_SCHEMA_ROWS = """
		SELECT * FROM "schema";
		""";

	@Language("SQL")
	private static final String SQL_TO_INSERT_SCHEMA_ROW = """
		INSERT INTO "schema" VALUES (%d, %d);
		""";

	@Language("SQL")
	private static final String SQL_TO_UPDATE_SCHEMA_ROW = """
		UPDATE "schema" SET "checksum" = %d WHERE id = %d;
		""";

	@Language("SQL")
	private static final String SQL_TO_DELETE_SCHEMA_ROW = """
		DELETE FROM "schema" WHERE id = %d;
		""";

	@Language("SQL")
	private static final String SQL_TO_UNLOCK = """
		SELECT pg_advisory_unlock(7478093087527115071);
		""";

	private final String group;
	private final Connection connection;
	private final HashSet<Migration> extraMigrations = new HashSet<>();
	private final DependencyStrategy dependencyStrategy;

	public Migrannotate(Connection connection) {
		this(DEFAULT_GROUP, connection);
	}

	public Migrannotate(String group, Connection connection) {
		this(group, connection, STABLE);
	}

	public Migrannotate(Connection connection, DependencyStrategy dependencyStrategy) {
		this(DEFAULT_GROUP, connection, dependencyStrategy);
	}

	public Migrannotate(String group, Connection connection, DependencyStrategy dependencyStrategy) {
		this.group = group;
		this.connection = connection;
		this.dependencyStrategy = dependencyStrategy;
	}

	public Migrannotate add(Migration extraMigration) {
		extraMigrations.add(extraMigration);
		return this;
	}

	public MigrationResult baseline() throws SQLException {
		return withLock(() -> {
			final var migrations = loadMigrationsExcept(emptyMap());
			final var sql = new StringBuilder();
			sql.append(SQL_TO_CREATE_SCHEMA_TABLE);
			sql.append(SQL_TO_TRUNCATE_SCHEMA_TABLE);
			for (final var migration : migrations) {
				final var id = migration.getId();
				final var latestChecksum = migration.migrate(EMPTY_CHECKSUM, new StringBuilder());
				sql.append(SQL_TO_INSERT_SCHEMA_ROW.formatted(id, latestChecksum));
			}
			try {
				execute(sql);
			} catch (SQLException exception) {
				throw new FailedToBaselineException(exception);
			}
			return MIGRATED;
		});
	}

	public MigrationResult migrate() throws SQLException, CannotUpgradeSchemaException, DuplicateSchemaIdentifierException {
		return withLock(() -> {
			final var currentChecksums = fetchCurrentChecksumsFromDatabase();
			var result = ALREADY_UP_TO_DATE;
			final var sql = new StringBuilder();
			for (final var migration : loadMigrationsExcept(currentChecksums)) {
				try {
					final var id = migration.getId();
					final var oldChecksum = currentChecksums.getOrDefault(id, EMPTY_CHECKSUM);
					final var newChecksum = migration.migrate(oldChecksum, sql);
					if (newChecksum == oldChecksum) {
						continue;
					}
					if (oldChecksum == EMPTY_CHECKSUM) {
						sql.append(SQL_TO_INSERT_SCHEMA_ROW.formatted(id, newChecksum));
					} else if (newChecksum == EMPTY_CHECKSUM) {
						sql.append(SQL_TO_DELETE_SCHEMA_ROW.formatted(id));
					} else {
						sql.append(SQL_TO_UPDATE_SCHEMA_ROW.formatted(newChecksum, id));
					}
					execute(sql);
					result = MIGRATED;
				} catch (SQLException exception) {
					throw new FailedToMigrateException(migration.getId(), exception);
				} finally {
					sql.setLength(0);
				}
			}
			return result;
		});
	}

	private MigrationResult withLock(Supplier<MigrationResult> supplier) throws SQLException {
		if (executeBooleanQuery(SQL_TO_LOCK)) {
			try {
				final var oldAutoCommit = connection.getAutoCommit();
				connection.setAutoCommit(false);
				try {
					return supplier.get();
				} catch (Throwable exception) {
					connection.rollback();
					throw exception;
				} finally {
					connection.setAutoCommit(oldAutoCommit);
				}
			} finally {
				executeBooleanQuery(SQL_TO_UNLOCK);
			}
		} else {
			return FAILED_TO_LOCK;
		}
	}

	private boolean executeBooleanQuery(String sql) throws SQLException {
		try (final var statement = connection.prepareStatement(sql)) {
			try (final var results = statement.executeQuery()) {
				return results.next() && results.getBoolean(1);
			}
		}
	}

	private void execute(StringBuilder sql) throws SQLException {
		execute(sql.toString());
	}

	private void execute(String sql) throws SQLException {
		try (final var statement = connection.prepareStatement(sql)) {
			var isResultSet = statement.execute();
			do {
				isResultSet = statement.getMoreResults();
			} while (isResultSet || statement.getUpdateCount() != -1);
		}
		connection.commit();
	}

	private Map<Long, Long> fetchCurrentChecksumsFromDatabase() {
		final var currentChecksumsById = new HashMap<Long, Long>();
		final var sql = SQL_TO_CREATE_SCHEMA_TABLE + SQL_TO_SELECT_ALL_SCHEMA_ROWS;
		try (final var statement = connection.prepareStatement(sql)) {
			var isResultSet = statement.execute();
			do {
				if (isResultSet) {
					try (final var results = statement.getResultSet()) {
						while (results.next()) {
							final var id = results.getLong(1);
							final var currentChecksum = results.getLong(2);
							currentChecksumsById.put(id, currentChecksum);
						}
					}
				}
				isResultSet = statement.getMoreResults();
			} while (isResultSet || statement.getUpdateCount() != -1);
		} catch (SQLException exception) {
			throw new RuntimeException(exception);
		}
		return currentChecksumsById;
	}

	private Set<Migration> loadMigrationsExcept(Map<Long, Long> currentChecksums) throws DuplicateSchemaIdentifierException {
		final var lookup = MethodHandles.lookup();
		final var classLoader = getSystemClassLoader();
		try {
			final var canonicalClassNamesById = new HashMap<Long, String>();
			final var resources = classLoader.getResources(group + ".migrannotate");
			final Set<Migration> unordered = switch (dependencyStrategy) {
				case UNSTABLE -> new HashSet<>();
				case STABLE -> new TreeSet<>(MIGRATION_COMPARATOR);
				case REVERSE_STABLE -> new TreeSet<>(MIGRATION_COMPARATOR.reversed());
			};
			for (final var extraMigration : extraMigrations) {
				final var id = extraMigration.getId();
				final var canonicalClassName = extraMigration.getClass().getCanonicalName();
				canonicalClassNamesById.put(id, canonicalClassName);
			}
			unordered.addAll(extraMigrations);
			while (resources.hasMoreElements()) {
				final var resource = resources.nextElement();
				try (final var inputStream = resource.openStream()) {
					try (final var dataInputStream = new DataInputStream(inputStream)) {
						while (true) {
							final var id = dataInputStream.readLong();
							final var latestChecksum = dataInputStream.readLong();
							final var pathToClassFile = dataInputStream.readUTF();
							final var canonicalClassName = pathToClassFile.replaceAll("/", ".");
							final var conflictingCanonicalClassName = canonicalClassNamesById.put(id, canonicalClassName);
							if (conflictingCanonicalClassName != null) {
								throw new DuplicateSchemaIdentifierException(canonicalClassName, conflictingCanonicalClassName);
							}
							if (latestChecksum != EMPTY_CHECKSUM) {
								final var currentChecksum = currentChecksums.getOrDefault(id, EMPTY_CHECKSUM);
								if (currentChecksum == latestChecksum) {
									continue;
								}
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
								unordered.add(migration);
							} else {
								throw new IllegalStateException();
							}
						}
					} catch (EOFException exception) {
						continue;
					}
				}
			}
			final var ordered = new LinkedHashSet<Migration>();
			while (unordered.removeIf(migration -> {
				for (final var otherMigration : unordered) {
					if (migration == otherMigration) {
						continue;
					}
					if (migration.isDependentOn(otherMigration)) {
						return false;
					}
				}
				return ordered.add(migration);
			})) {}
			if (unordered.isEmpty()) {
				return ordered;
			}
			throw new IllegalStateException();
		} catch (IOException | IllegalAccessException | NoSuchMethodException | InstantiationException | InvocationTargetException exception) {
			throw new RuntimeException(exception);
		}
	}

}