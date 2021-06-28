package com.leaprnd.migrannotate;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Collections.singleton;
import static javax.lang.model.element.ElementKind.CLASS;
import static javax.tools.Diagnostic.Kind.ERROR;
import static javax.tools.StandardLocation.CLASS_OUTPUT;

public class MigrateAnnotationProcessor extends AbstractMigrannotateAnnotationProcessor {

	private final ConcurrentHashMap<String, DataOutputStream> manifests = new ConcurrentHashMap<>();

	@Override
	public Set<String> getSupportedAnnotationTypes() {
		return singleton(Migrate.class.getCanonicalName());
	}

	@Override
	public synchronized void init(ProcessingEnvironment processingEnv) {
		manifests.clear();
		super.init(processingEnv);
	}

	@Override
	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
		final var messager = processingEnv.getMessager();
		for (final var element : roundEnv.getElementsAnnotatedWith(Migrate.class)) {
			if (element.getKind() != CLASS) {
				messager.printMessage(ERROR, "Only classes can be annotated with @Migrate!", element);
				continue;
			}
			final var manifest = element.getAnnotation(Migrate.class);
			final var outputStream = manifests.computeIfAbsent(manifest.group(), this::openOutputStream);
			try {
				outputStream.writeLong(manifest.id());
				outputStream.writeLong(manifest.latestChecksum());
				outputStream.writeUTF(getFullyQualifiedPathTo(element));
				outputStream.flush();
			} catch (IOException exception) {
				messager.printMessage(ERROR, "Cannot write manifest class to metadata file!", element);
			}
		}
		return true;
	}

	private DataOutputStream openOutputStream(String group) {
		final var filter = processingEnv.getFiler();
		try {
			return new DataOutputStream(filter.createResource(CLASS_OUTPUT, "", group + ".migrannotate").openOutputStream());
		} catch (IOException exception) {
			throw new RuntimeException(exception);
		}
	}

	private String getFullyQualifiedPathTo(Element element) {
		return getPackageNameOf(element).replace('.', '/') + '/' + element.getSimpleName() + ".class";
	}

}
