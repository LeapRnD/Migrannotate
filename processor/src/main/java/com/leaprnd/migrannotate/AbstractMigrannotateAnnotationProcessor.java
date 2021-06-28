package com.leaprnd.migrannotate;

import javax.annotation.processing.AbstractProcessor;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;

import java.util.Set;

import static java.util.Collections.singleton;
import static javax.lang.model.SourceVersion.latestSupported;

public abstract class AbstractMigrannotateAnnotationProcessor extends AbstractProcessor {

	protected static final String PACKAGE = "com.leaprnd.migrannotate";

	@Override
	public Set<String> getSupportedOptions() {
		return singleton("org.gradle.annotation.processing.aggregating");
	}

	@Override
	public SourceVersion getSupportedSourceVersion() {
		return latestSupported();
	}

	protected String getCanonicalNameOf(Element element) {
		if (element instanceof final TypeElement typeElement) {
			return typeElement.getQualifiedName().toString();
		} else {
			return getPackageNameOf(element) + '.' + element.getSimpleName();
		}
	}

	protected String getPackageNameOf(Element element) {
		return processingEnv.getElementUtils().getPackageOf(element).getQualifiedName().toString();
	}

}
