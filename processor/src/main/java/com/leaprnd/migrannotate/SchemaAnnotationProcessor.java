package com.leaprnd.migrannotate;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;

import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.SimpleTypeVisitor14;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.zip.CRC32;

import static com.leaprnd.migrannotate.Migration.DEFAULT_GROUP;
import static com.leaprnd.migrannotate.Migration.enquoteIdentifier;
import static com.leaprnd.migrannotate.Migration.enquoteLiteral;
import static com.leaprnd.migrannotate.Migration.getHigherOrderBitsOf;
import static com.leaprnd.migrannotate.Migration.getLowerOrderBitsOf;
import static com.squareup.javapoet.TypeName.BOOLEAN;
import static com.squareup.javapoet.TypeName.LONG;
import static java.lang.Character.isWhitespace;
import static java.lang.Math.abs;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static java.util.Set.of;
import static javax.lang.model.element.ElementKind.ENUM_CONSTANT;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.STATIC;
import static javax.tools.Diagnostic.Kind.ERROR;

public class SchemaAnnotationProcessor extends AbstractMigrannotateAnnotationProcessor {

	private static final String CURRENT_CHECKSUM_NAME = "currentChecksum";
	private static final String ID_NAME = "ID";
	private static final String LATEST_CHECKSUM_NAME = "LATEST_CHECKSUM";
	private static final String LATEST_NORMAL_CHECKSUM_NAME = "LATEST_NORMAL_CHECKSUM";
	private static final String LATEST_REPEATABLE_CHECKSUM_NAME = "LATEST_REPEATABLE_CHECKSUM";
	private static final String OTHER_MIGRATION_NAME = "otherMigration";
	private static final long REPEATABLE_CHECKSUM_MASK = 0xFFFFFFFF00000000L;
	private static final long NORMAL_CHECKSUM_MASK = 0x00000000FFFFFFFFL;

	private static final Set<Class<? extends Annotation>> SUPPORTED_ANNOTATION_TYPES = of(
		SchemaUpgrades.class,
		SchemaUpgrade.class,
		EnumSchema.class,
		EnumSchemas.class,
		RepeatableSchema.class,
		Schema.class
	);

	private static long computeChecksumOfSql(String value) {
		final var crc32 = new CRC32();
		crc32.update(value.getBytes(UTF_8));
		return crc32.getValue();
	}

	@Override
	public Set<String> getSupportedAnnotationTypes() {
		final var names = new LinkedHashSet<String>();
		for (final var type : SUPPORTED_ANNOTATION_TYPES) {
			names.add(type.getCanonicalName());
		}
		return names;
	}

	@Override
	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
		final var filter = processingEnv.getFiler();
		final var messager = processingEnv.getMessager();
		for (final var element : roundEnv.getElementsAnnotatedWithAny(SUPPORTED_ANNOTATION_TYPES)) {
			if (switch (element.getKind()) {
				case ENUM, CLASS, INTERFACE, RECORD -> false;
				default -> true;
			}) {
				final var message = "Only enums, classes, interfaces and records can be annotated with @Schema!";
				messager.printMessage(ERROR, message, element);
				continue;
			}
			try {
				final var builder = new JavaFileBuilder(element);
				final var file = builder.build();
				try {
					file.writeTo(filter);
				} catch (IOException exception) {
					messager.printMessage(ERROR, "Unable to write generated class!", element);
					throw new RuntimeException(exception);
				}
			} catch (InvalidSQLException exception) {
				final var message = format("SQL of %s should be terminated with a semicolon!", exception.getAnnotation());
				messager.printMessage(ERROR, message, element);
			} catch (InvalidDependencyException exception) {
				final var name = exception.getDependency().getSimpleName();
				final var message = format(
					"%s is an invalid dependency because it is not annotated with @SchemaIdentifier!",
					name
				);
				messager.printMessage(ERROR, message, element);
			} catch (MissingSchemaIdentifierException exception) {
				messager.printMessage(ERROR, "Must be annotated with @SchemaIdentifier!", element);
			} catch (CyclicalDependencyException exception) {
				messager.printMessage(ERROR, "Schema depends on itself!", element);
			} catch (InvalidEnumSchemaValue exception) {
				final var message = format("%s is not a valid value for @EnumSchema!", exception.getElement().getSimpleName());
				messager.printMessage(ERROR, message, element);
			}
		}
		return true;
	}

	protected long getIdFor(Element element) {
		final var schemaIdentifier = element.getAnnotation(SchemaIdentifier.class);
		if (schemaIdentifier != null) {
			return schemaIdentifier.value();
		}
		throw new MissingSchemaIdentifierException(element);
	}

	private class JavaFileBuilder {

		protected final Element annotatedClass;

		public JavaFileBuilder(Element annotatedClass) {
			this.annotatedClass = annotatedClass;
		}

		public JavaFile build() {
			final var className = ClassName.get(PACKAGE, getSimpleClassName());
			return JavaFile
				.builder(PACKAGE, toTypeSpec())
				.skipJavaLangImports(true)
				.indent("\t")
				.addStaticImport(className, ID_NAME)
				.addStaticImport(className, LATEST_CHECKSUM_NAME)
				.addStaticImport(Migration.class, "getLowerOrderBitsOf")
				.addStaticImport(Migration.class, "getHigherOrderBitsOf")
				.build();
		}

		private String getSimpleClassName() {
			return "Migration" + abs(getIdFor(annotatedClass));
		}

		private TypeSpec toTypeSpec() {
			return TypeSpec
				.classBuilder(getSimpleClassName())
				.addOriginatingElement(annotatedClass)
				.addAnnotation(migrateAnnotationSpec())
				.addModifiers(PUBLIC, FINAL)
				.addSuperinterface(Migration.class)
				.addField(idSpec())
				.addField(latestNormalChecksumSpec())
				.addField(latestRepeatableChecksumSpec())
				.addField(latestChecksumSpec())
				.addMethod(getIdSpec())
				.addMethod(getLatestChecksumSpec())
				.addMethod(migrateMethodSpec())
				.addMethod(isDependentOnMigrationSpec())
				.addMethod(isDependentOnIdSpec())
				.build();
		}

		private AnnotationSpec migrateAnnotationSpec() {
			return AnnotationSpec
				.builder(Migrate.class)
				.addMember("id", "$L", ID_NAME)
				.addMember("latestChecksum", "$L", LATEST_CHECKSUM_NAME)
				.addMember("group", "$S", group())
				.build();
		}

		private String group() {
			final var schemaGroup = annotatedClass.getAnnotation(SchemaGroup.class);
			if (schemaGroup == null) {
				return DEFAULT_GROUP;
			}
			return schemaGroup.value();
		}

		private FieldSpec idSpec() {
			final var id = getIdFor(annotatedClass);
			return FieldSpec.builder(LONG, ID_NAME, PUBLIC, STATIC, FINAL).initializer("$LL", id).build();
		}

		private FieldSpec latestNormalChecksumSpec() {
			return FieldSpec
				.builder(LONG, LATEST_NORMAL_CHECKSUM_NAME, PRIVATE, STATIC, FINAL)
				.initializer("$LL", latestNormalChecksum())
				.build();
		}

		private long latestNormalChecksum() {
			final var normalSchema = annotatedClass.getAnnotation(Schema.class);
			if (normalSchema != null) {
				final var sql = normalSchema.value();
				if (isTerminatedWithSemicolin(sql)) {
					return computeChecksumOfSql(sql);
				}
				throw new InvalidSQLException(annotatedClass, "@Schema");
			} else {
				return 0L;
			}
		}

		private static boolean isTerminatedWithSemicolin(String sql) {
			for (var index = sql.lastIndexOf(';') + 1; index < sql.length(); index ++) {
				if (isWhitespace(sql.charAt(index))) {
					continue;
				}
				return false;
			}
			return true;
		}

		private FieldSpec latestRepeatableChecksumSpec() {
			return FieldSpec
				.builder(LONG, LATEST_REPEATABLE_CHECKSUM_NAME, PRIVATE, STATIC, FINAL)
				.initializer("$LL", latestRepeatableChecksum())
				.build();
		}

		private long latestRepeatableChecksum() {
			var checksum = 0L;
			final var repeatableSchema = annotatedClass.getAnnotation(RepeatableSchema.class);
			if (repeatableSchema != null) {
				final var sql = repeatableSchema.value();
				if (isTerminatedWithSemicolin(sql)) {
					checksum ^= computeChecksumOfSql(sql);
				} else {
					throw new InvalidSQLException(annotatedClass, "@RepeatableSchema");
				}
			}
			final var enumSql = enumSql();
			if (!enumSql.isEmpty()) {
				checksum ^= computeChecksumOfSql(enumSql);
			}
			return checksum << 32;
		}

		private FieldSpec latestChecksumSpec() {
			return FieldSpec
				.builder(LONG, LATEST_CHECKSUM_NAME, PUBLIC, STATIC, FINAL)
				.initializer("$L | $L", LATEST_NORMAL_CHECKSUM_NAME, LATEST_REPEATABLE_CHECKSUM_NAME)
				.build();
		}

		private String enumSql() {
			final var sql = new StringBuilder();
			for (final var enumSchema : annotatedClass.getAnnotationsByType(EnumSchema.class)) {
				final var name = enumSchema.name();
				final var identifier = enquoteIdentifier(name);
				sql.append(format("""
					DO $$ BEGIN
						IF %s NOT IN (
							SELECT T.typname FROM
								pg_type AS T JOIN
								pg_namespace AS N ON N.oid = T.typnamespace
							WHERE
								N.nspname = CURRENT_SCHEMA()
						) THEN
							CREATE TYPE %s AS ENUM ();
						END IF;
					END; $$;
					""", enquoteLiteral(name), identifier));
				final var values = toTypeMirror(enumSchema::value).accept(VALUE_VISITOR, new ArrayList<>());
				for (final var valueToIgnore : enumSchema.valuesToIgnore()) {
					values.remove(valueToIgnore);
				}
				final var iterator = values.listIterator(values.size());
				if (iterator.hasPrevious()) {
					final var last = enquoteLiteral(iterator.previous());
					sql.append(format("ALTER TYPE %s ADD VALUE IF NOT EXISTS %s;\n", identifier, last));
					var previous = last;
					while (iterator.hasPrevious()) {
						final var value = enquoteLiteral(iterator.previous());
						sql.append(format("ALTER TYPE %s ADD VALUE IF NOT EXISTS %s BEFORE %s;\n", identifier, value, previous));
						previous = value;
					}
				}
			}
			return sql.toString();
		}

		private MethodSpec getIdSpec() {
			return MethodSpec
				.methodBuilder("getId")
				.addAnnotation(Override.class)
				.addModifiers(PUBLIC, FINAL)
				.returns(LONG)
				.addStatement("return $L", ID_NAME)
				.build();
		}

		private MethodSpec getLatestChecksumSpec() {
			return MethodSpec
				.methodBuilder("getLatestChecksum")
				.addAnnotation(Override.class)
				.addModifiers(PUBLIC, FINAL)
				.returns(LONG)
				.addStatement("return $L", LATEST_CHECKSUM_NAME)
				.build();
		}

		private MethodSpec migrateMethodSpec() {
			return MethodSpec
				.methodBuilder("migrate")
				.addAnnotation(Override.class)
				.addModifiers(PUBLIC, FINAL)
				.addParameter(LONG, CURRENT_CHECKSUM_NAME)
				.addParameter(SQLWriter.class, "sql")
				.addCode(migrateMethodCode())
				.build();
		}

		private CodeBlock migrateMethodCode() {
			final var code = CodeBlock.builder();
			code
				.addStatement(
					"final var repeat = ($L & $LL) != $L",
					CURRENT_CHECKSUM_NAME,
					REPEATABLE_CHECKSUM_MASK,
					LATEST_REPEATABLE_CHECKSUM_NAME
				);
			code.addStatement("$L &= $LL", CURRENT_CHECKSUM_NAME, NORMAL_CHECKSUM_MASK);
			final var enumSQL = enumSql();
			if (!enumSQL.isEmpty()) {
				code.beginControlFlow("if (repeat)").addStatement("sql.appendToPrologue($S)", enumSQL).endControlFlow();
			}
			final var normalSchema = annotatedClass.getAnnotation(Schema.class);
			if (normalSchema != null) {
				code
					.beginControlFlow("if ($L == EMPTY_CHECKSUM)", CURRENT_CHECKSUM_NAME)
					.addStatement("sql.append($S)", normalSchema.value())
					.nextControlFlow("else");
			}
			for (final var upgrade : annotatedClass.getAnnotationsByType(SchemaUpgrade.class)) {
				code
					.beginControlFlow("if ($L == $LL)", CURRENT_CHECKSUM_NAME, upgrade.from())
					.addStatement("sql.append($S)", upgrade.sql())
					.addStatement("$L = $LL", CURRENT_CHECKSUM_NAME, upgrade.to())
					.endControlFlow();
			}
			code
				.beginControlFlow("if ($L != $L)", CURRENT_CHECKSUM_NAME, LATEST_NORMAL_CHECKSUM_NAME)
				.addStatement(
					"throw new $T($S, $L, $L)",
					CannotUpgradeSchemaException.class,
					getCanonicalNameOf(annotatedClass),
					CURRENT_CHECKSUM_NAME,
					LATEST_NORMAL_CHECKSUM_NAME
				)
				.endControlFlow();
			if (normalSchema != null) {
				code.endControlFlow();
			}
			final var repeatableSchema = annotatedClass.getAnnotation(RepeatableSchema.class);
			if (repeatableSchema != null) {
				code
					.beginControlFlow("if (repeat)", LATEST_CHECKSUM_NAME, REPEATABLE_CHECKSUM_MASK)
					.addStatement("sql.append($S)", repeatableSchema.value())
					.endControlFlow();
			}
			return code.build();
		}

		private MethodSpec isDependentOnMigrationSpec() {
			return MethodSpec
				.methodBuilder("isDependentOn")
				.addAnnotation(Override.class)
				.addModifiers(PUBLIC, FINAL)
				.returns(BOOLEAN)
				.addParameter(Migration.class, OTHER_MIGRATION_NAME)
				.addStatement("return isDependentOn($L.getId())", OTHER_MIGRATION_NAME)
				.build();
		}

		private MethodSpec isDependentOnIdSpec() {
			return MethodSpec
				.methodBuilder("isDependentOn")
				.addModifiers(PUBLIC, STATIC)
				.returns(BOOLEAN)
				.addParameter(LONG, "id")
				.addCode(isDependentOnIdCode())
				.build();
		}

		// https://stackoverflow.com/questions/2676210/why-cant-your-switch-statement-data-type-be-long-java
		private CodeBlock isDependentOnIdCode() {
			final var code = CodeBlock.builder();
			final var dependencies = findDependenciesOf(annotatedClass);
			if (dependencies.isEmpty()) {
				code.addStatement("return false");
			} else {
				code.beginControlFlow("return switch (getLowerOrderBitsOf(id))");
				final var firstIterator = dependencies.iterator();
				code.add("case $L", getLowerOrderBitsOf(firstIterator.next()));
				while (firstIterator.hasNext()) {
					code.add(", $L", getLowerOrderBitsOf(firstIterator.next()));
				}
				code.beginControlFlow(" -> switch (getHigherOrderBitsOf(id))");
				final var secondIterator = dependencies.iterator();
				code.add("case $L", getHigherOrderBitsOf(secondIterator.next()));
				while (secondIterator.hasNext()) {
					code.add(", $L", getHigherOrderBitsOf(secondIterator.next()));
				}
				code.addStatement(" -> true");
				code.addStatement("default -> false");
				code.endControlFlow("");
				code.addStatement("default -> false");
				code.endControlFlow("");
			}
			return code.build();
		}

	}

	private static DeclaredType toTypeMirror(Supplier<Class<?>> value) {
		try {
			value.get();
			throw new IllegalStateException();
		} catch (MirroredTypeException exception) {
			final var typeMirror = exception.getTypeMirror();
			if (typeMirror instanceof final DeclaredType declaredType) {
				return declaredType;
			}
			throw new IllegalStateException();
		}
	}

	private static final SimpleTypeVisitor14<List<String>, List<String>> VALUE_VISITOR = new SimpleTypeVisitor14<>() {

		@Override
		public List<String> visitDeclared(DeclaredType declaredType, List<String> values) {
			final var element = declaredType.asElement();
			switch (element.getKind()) {
				case ENUM -> {
					for (final var enclosed : element.getEnclosedElements()) {
						if (enclosed.getKind() == ENUM_CONSTANT) {
							final var value = enclosed.getSimpleName().toString();
							values.add(value);
						}
					}
				}
				case CLASS, INTERFACE -> {
					final var subTypes = element.getAnnotation(JsonSubTypes.class);
					if (subTypes == null) {
						throw new InvalidEnumSchemaValue(element);
					}
					for (final var subType : subTypes.value()) {
						final var names = subType.names();
						if (names.length > 0) {
							values.addAll(asList(names));
							continue;
						}
						final var name = subType.name();
						if (name.length() > 0) {
							values.add(name);
							continue;
						}
						final var typeName = toTypeMirror(subType::value).asElement().getAnnotation(JsonTypeName.class);
						if (typeName == null) {
							throw new InvalidEnumSchemaValue(element);
						}
						values.add(typeName.value());
					}
				}
				default -> throw new InvalidEnumSchemaValue(element);
			}
			return values;
		}

		@Override
		protected List<String> defaultAction(TypeMirror mirror, List<String> values) {
			return values;
		}

	};

	private Set<Long> findDependenciesOf(Element element) {
		final var dependencies = new HashSet<Long>();
		try {
			dependencyWalker.walkImplicitDependenciesOf(element, dependencies);
			dependencyWalker.walkExplicitDependenciesOf(element, dependencies);
		} catch (MissingSchemaIdentifierException exception) {
			throw new InvalidDependencyException(exception.getElement());
		}
		if (dependencies.contains(getIdFor(element))) {
			throw new CyclicalDependencyException();
		}
		return dependencies;
	}

	private final DependencyWalker dependencyWalker = new DependencyWalker(false);
	private final DependencyWalker silentDependencyWalker = new DependencyWalker(true);

	private class DependencyWalker extends SimpleTypeVisitor14<Void, Set<Long>> {

		private final boolean silent;

		public DependencyWalker(boolean silent) {
			this.silent = silent;
		}

		@Override
		public Void visitDeclared(DeclaredType declaredType, Set<Long> dependencies) {
			final var element = declaredType.asElement();
			if (tryAdd(element, dependencies)) {
				walkImplicitDependenciesOf(element, dependencies);
				walkExplicitDependenciesOf(element, dependencies);
			}
			return null;
		}

		private boolean tryAdd(Element element, Set<Long> dependencies) {
			try {
				return dependencies.add(getIdFor(element));
			} catch (MissingSchemaIdentifierException exception) {
				if (silent) {
					return true;
				} else {
					throw exception;
				}
			}
		}

		private void walkExplicitDependenciesOf(Element element, Set<Long> dependencies) {
			for (final var dependency : element.getAnnotationsByType(SchemaDependency.class)) {
				toTypeMirror(dependency::value).accept(dependencyWalker, dependencies);
			}
		}

		private void walkImplicitDependenciesOf(Element element, Set<Long> dependencies) {
			walkImplicitDependenciesOf(element.asType(), dependencies);
		}

		private void walkImplicitDependenciesOf(TypeMirror element, Set<Long> dependencies) {
			final var types = processingEnv.getTypeUtils();
			for (final var supertype : types.directSupertypes(element)) {
				supertype.accept(silentDependencyWalker, dependencies);
			}
		}

	}

}