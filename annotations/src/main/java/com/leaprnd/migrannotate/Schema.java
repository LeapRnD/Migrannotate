package com.leaprnd.migrannotate;

import org.intellij.lang.annotations.Language;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.SOURCE;

@Target(TYPE)
@Retention(SOURCE)
public @interface Schema {
	@Language("SQL")
	String value();
}