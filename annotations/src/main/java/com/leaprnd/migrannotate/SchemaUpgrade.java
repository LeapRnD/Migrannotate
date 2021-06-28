package com.leaprnd.migrannotate;

import org.intellij.lang.annotations.Language;

import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.SOURCE;

@Target(TYPE)
@Retention(SOURCE)
@Repeatable(SchemaUpgrades.class)
public @interface SchemaUpgrade {
	long from();
	long to();
	@Language("SQL")
	String sql();
}