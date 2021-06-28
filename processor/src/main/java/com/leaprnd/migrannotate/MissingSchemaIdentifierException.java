package com.leaprnd.migrannotate;

import javax.lang.model.element.Element;

public class MissingSchemaIdentifierException extends RuntimeException {

	private final Element element;

	public MissingSchemaIdentifierException(Element element) {
		this.element = element;
	}

	public Element getElement() {
		return element;
	}

}
