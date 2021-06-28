package com.leaprnd.migrannotate;

import javax.lang.model.element.Element;

public class InvalidEnumSchemaValue extends RuntimeException {

	private final Element element;

	public InvalidEnumSchemaValue(Element element) {
		this.element = element;
	}

	public Element getElement() {
		return element;
	}

}
