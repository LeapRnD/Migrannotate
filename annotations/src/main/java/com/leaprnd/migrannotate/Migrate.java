package com.leaprnd.migrannotate;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static com.leaprnd.migrannotate.Migration.DEFAULT_GROUP;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.SOURCE;

@Target(TYPE)
@Retention(SOURCE)
public @interface Migrate {
	long id();
	long latestChecksum() default 0L;
	String group() default DEFAULT_GROUP;
}