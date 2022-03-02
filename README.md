# Migrannotate

![Maven](https://badgen.net/maven/v/maven-central/com.leaprnd.migrannotate/runtime) [![Tests](https://github.com/LeapRnD/Migrannotate/actions/workflows/test.yml/badge.svg)](https://github.com/LeapRnD/Migrannotate/actions)

Migrannotate small, fast Java library for managing database schema changes via annotations. It fulfills a similar purpose to [Flyway](https://flywaydb.org/) or [Liquibase](https://www.liquibase.org/) but with several very important architectural differences.

In contrast to most database schema management tools, with Migrannotate, you specify the **latest** schema as well as the sequence of migrations to upgrade from any old schema to the latest schema. This has a number of important practical implications:

1. After enough time has passed that you know you will no longer need to restore an old database snapshot, you can safely remove old migrations scripts. This prevents your codebase from accumulating more and more migrations as time passes.

2. Because the checksum of the latest schema can be computed at compile-time, Migrannotate typically takes less than 100 milliseconds to migrate a database when there are no changes necessary.

3. As opposed to other migration tools, where you need to use a database tool to figure out what your current schema is, when you use Migrannotate your codebase will always include the latest schema, front and center.

## Warning

Though this project is based on [JDBC](https://en.wikipedia.org/wiki/Java_Database_Connectivity), it has been developed and tested exclusively with [PostgreSQL](https://www.postgresql.org/). It will not work with other databases at this time. Pull requests welcome!

## Usage

First you will need to add Migrannotate to your project. If you are using [Gradle](https://gradle.org/), you will need to add the following dependencies to your `build.gradle` file:

```groovy
dependencies {
    compileOnly group: "org.jetbrains", name: "annotations", version: "21.0.1"
    annotationProcessor group: "com.leaprnd.migrannotate", name: "processor", version: "1.0.9"
    api group: "com.leaprnd.migrannotate", name: "annotations", version: "1.0.9"
    implementation group: "com.leaprnd.migrannotate", name: "runtime", version: "1.0.9"
}
```

Once that it done, you can begin annotating your classes with the `@SchemaIdentifier` annotation to uniquely identify them. This allows the annotated class to be renamed or moved to a different package without breaking Migrannotate.

```java
package com.example;

import com.leaprnd.migrannotate.SchemaIdentifier;

@SchemaIdentifier(162454771132063733L)
public class PersonRepository {
    // ...
}
```

The value of every `@SchemaIdentifier` annotation (i.e. `162454771132063733L` in the example above) **must be unique** across your project. Your best bet is to just generate a random number by running `new Random().nextLong()` in [JShell](https://docs.oracle.com/javase/9/jshell/introduction-jshell.htm#JSHEL-GUID-630F27C8-1195-4989-9F6B-2C51D46F52C8).

Once you've identified a class, you can add a `@Schema` annotation to specify the most recent version of the database schema associated with it. When Migrannotate encounters an empty database—or more accurately, a database _without_ an old version of this schema—this is the script it will run.

```java
package com.example;

import com.leaprnd.migrannotate.Schema;
import com.leaprnd.migrannotate.SchemaIdentifier;

@SchemaIdentifier(162454771132063733L)
@Schema("""
CREATE TABLE person (
    id SERIAL PRIMARY KEY
);
""")
public class PersonRepository {
    // ...
}
```

Then just call Migrannotate when your application starts:

```java
package com.example;

import com.leaprnd.migrannotate.Migrannotate;
import java.sql.Connection;
import java.sql.SQLException;

public class YourApplication {
    public static void main(String ... arguments) throws SQLException {
        final Connection connection = // ...
        new Migrannotate(connection).migrate();
    }
}
```

When it comes time to change your schema, for example by adding a column, you can simply update the `@Schema` annotation accordingly:

```java
@Schema(sql = """
CREATE TABLE person (
    id SERIAL PRIMARY KEY,
    name VARCHAR NOT NULL
);
""")
```

This will work fine on an empty database, but if you try to migrate a database with the old schema, Migrannotate will throw an exception:

```
com.leaprnd.migrannotate.CannotUpgradeSchemaException:
    There is no @SchemaUpgrade(from = 1558651051061441123L, to = 1042910928393793285L, sql = ...) annotation on PersonRepository!
```

To solve this error, we can add a `@SchemaUpgrade` annotation to `PersonRepository`:

```java
@SchemaUpgrade(from = 1558651051061441123L, to = 1042910928393793285L, sql = """
ALTER TABLE person ADD COLUMN name VARCHAR NOT NULL;
""")
```

You will need to keep the `@SchemaUpgrade` in your codebase until you are 100% sure that all your databases (and backups!) have been migrated. After that, it can safely be removed.

### Repeatable Schemas

If there is a portion of your schema that can be safely rerun when it changes, you can add a `@RepeatableSchema` annotation to your class.

```java
@RepeatableSchema("""
CREATE OR REPLACE FUNCTION TO_JSON(P person) RETURNS JSONB AS $$
    SELECT JSONB_BUILD_OBJECT(
        'id',
        P.id,
        'name',
        P.name
    );
$$ LANGUAGE SQL IMMUTABLE RETURNS NULL ON NULL INPUT;
""")
```

Note that, if both are present, `@RepeatableSchema` always runs **after** `@Schema`.

### Dependencies

Often, the schema for a class will depend on the schema of other `@Schema`-annotated classes. You can add one or more `@SchemaDependency` annotations to your class to ensure Migrannotate runs them the correct order.

```java
@SchemaDependency(JobRespository.class)
@SchemaDependency(CountryRepository.class)
```

### Grouping

By default, all `@Schema` are upgraded when you call `migrate()` on a `Migrannotate`, but if you need to, you can specify which _group_ a schema belongs to by adding an `@SchemaGroup` annotation to your class.

```java
@SchemaGroup("example")
```

You can then instruct Migrannotate to only update schemas that belong to a particular group.

```java
new Migrannotate("example", connection).migrate();
```

When using Migrannotate within a library, it is recommended to specify a `group` to avoid collisions with consumers of your library that are also using Migrannotate.

### Testing

We recommend creating a functional test to verify that none of your `@Schema` annotations fail. Fortunately, [TestContainers](https://www.testcontainers.org/) makes this pretty simple.

First you will need to add some dependencies to your `build.gradle`:

```groovy
dependencies {
    testImplementation platform("org.testcontainers:testcontainers-bom:1.15.3")
    testImplementation group: "org.testcontainers", name: "jdbc"
    testImplementation group: "org.testcontainers", name: "postgresql"
    testImplementation group: "org.junit.jupiter", name: "junit-jupiter", version: "5.7.2"
    testImplementation group: "org.postgresql", name: "postgresql", version: "42.2.23"
}
```

You can then add a test class to your project that looks _something_ like this:

```java
package com.example;

import com.leaprnd.migrannotate.Migrannotate;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

import static com.leaprnd.migrannotate.ExecutionDirection.FORWARD;
import static com.leaprnd.migrannotate.ExecutionDirection.BACKWARD;
import static com.leaprnd.migrannotate.MigrationResult.ALREADY_UP_TO_DATE;
import static com.leaprnd.migrannotate.MigrationResult.MIGRATED;
import static java.util.EnumSet.of;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class MigrannotateTest {

    static {
        try {
            Class.forName("org.testcontainers.jdbc.ContainerDatabaseDriver");
        } catch (ClassNotFoundException exception) {
            fail(exception);
        }
    }

    // TODO: Update this to match your production environment
    private static final String VERSION = "13.3";
    
    @Test
    public void testMigrate() throws SQLException {
        final var connection = DriverManager.getConnection("jdbc:tc:postgresql:" + VERSION + ":///test");
        for (final var executionDirection : of(FORWARD, BACKWARD)) {
            try (final var statement = connection.createStatement()) {
                statement.executeUpdate("DROP SCHEMA IF EXISTS public CASCADE");
                statement.executeUpdate("CREATE SCHEMA public");
            }
            final var migrannotate = new Migrannotate(connection, executionDirection);
            assertEquals(MIGRATED, migrannotate.migrate());
            assertEquals(ALREADY_UP_TO_DATE, migrannotate.migrate());
        }
    }
    
}
```

Doing the migration twice with different execution directions guarantees that you aren't missing a `@SchemaDependency`.

## Other Features

* Migrannotate executes everything in one big transaction, so if _any_ migration fails, the transaction will rollback and leave your database untouched. This behavior is well-suited for continuous deployment. 

* You do **not** need to make sure that all your `@Schema` annotations are in the same project. Migrannotate searches the entire class path when you call `migrate()`. This makes it well-suited to multi-module applications.

* Migrannotate loads the classes that it generates as [hidden classes](https://openjdk.java.net/jeps/371) so that they can be garbage-collected. This means that Migrannotate retains **almost no memory** after migrating.

* If you are using [IntelliJ](https://www.jetbrains.com/idea/), the SQL syntax within the `@Schema`, `@RepeatableSchema` and `@SchemaUpgrade` annotations will be highlighted properly so long as you don't disable [the IntelliLang plugin](https://plugins.jetbrains.com/plugin/13374-intellilang).