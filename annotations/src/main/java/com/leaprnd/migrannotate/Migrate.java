package com.leaprnd.migrannotate;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static com.leaprnd.migrannotate.Migration.DEFAULT_GROUP;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.SOURCE;

@Target(TYPE)
@Retention(SOURCE)
public @interface Migrate {

	long UNKNOWN_AT_COMPILE_TIME = -1395564138469529021L;

	long id();
	long latestChecksum() default UNKNOWN_AT_COMPILE_TIME;
	String group() default DEFAULT_GROUP;

}