package com.leaprnd.migrannotate;

import javax.lang.model.element.Element;

public class InvalidSQLException extends RuntimeException {

	private final Element element;
	private final String annotation;

	public InvalidSQLException(Element element, String annotation) {
		this.element = element;
		this.annotation = annotation;
	}

	public Element getElement() {
		return element;
	}

	public String getAnnotation() {
		return annotation;
	}

}
