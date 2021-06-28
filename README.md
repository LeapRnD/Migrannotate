# Migrannotate

[![Java CI](https://github.com/Leap-R-D/Migrannotate/actions/workflows/test.yml/badge.svg)](https://github.com/Leap-R-D/Migrannotate/actions)

Migrannotate small, fast Java library for managing database schema changes via annotations. It fulfills a similar purpose to [Flyway](https://flywaydb.org/) or [Liquibase](https://www.liquibase.org/) but with several very important architectural differences.

In contrast to most database schema management tools, with Migrannotate, you specify the **latest** schema as well as the sequence of migrations to upgrade from any old schema to the latest schema. This has a number of important practical implications:

1. After enough time has passed that you know you will no longer need to restore a database snapshot, you can safely remove old migrations scripts. This prevents your codebase from accumulating more and more migrations as time passes.

2. Because the checksum of the latest schema can be computed at compile-time, Migrannotate typically takes less than 100 milliseconds to migrate a database when there are no changes necessary.

3. As opposed to other migration tools, where you need to use a database tool to figure out what your current schema is, when you use Migrannotate your codebase will always include the latest schema, front and center.

4. There are no rollbacks! If you deploy a bad release, and your schema changes aren't backwards compatible, your only option is to press on. In practice, this shouldn't inconvenience many developers, since most of us are too lazy to write and test rollback scripts.

## Warning!

This project is currently only tested with PostgreSQL. It will probably not work with other databases at this time.

## How it works

After you add Migrannotate to your project, you can begin annotating your classes.

Each class will need a `@SchemaIdentifier` annotation to uniquely identify it. This allows the annotated class to be renamed or moved to a different package without breaking Migrannotate.


```java
package com.example;

import com.leaprnd.migrannotate.SchemaIdentifier;

@SchemaIdentifier(162454771132063733L)
public class PersonRepository {
    // ...
}
```

You can then add a `@Schema` annotation to specify the most recent version of the database schema associated with a particular class. When Migrannotate encounters an empty database—or more accurately, a database _without_ an old version of this schema—this is the script it will run.

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
new Migrannotate(connection).migrate();
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

## Bonus Features

* You do **not** need to make sure that all your `@Schema` annotations are in the same project. Migrannotate searches the entire class path when `migrate()`; it is well-suited to multi-module applications.


* Migrannotate loads the classes that it generates as [hidden classes](https://openjdk.java.net/jeps/371) so that they can be garbage-collected once migration is complete. If you Migrannotate when you start your application, it will retain **almost zero** memory usage once it's done.


* So long as you don't disable the IntelliLang plugin, IntelliJ will properly highlight the syntax within the `@Schema`, `@RepeatableSchema` and `@SchemaUpgrade` annotations. 
