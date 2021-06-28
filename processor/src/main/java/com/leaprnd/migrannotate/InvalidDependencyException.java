package com.leaprnd.migrannotate;

import javax.lang.model.element.Element;

public class InvalidDependencyException extends RuntimeException {

	private final Element dependency;

	public InvalidDependencyException(Element dependency) {
		this.dependency = dependency;
	}

	public Element getDependency() {
		return dependency;
	}

}
