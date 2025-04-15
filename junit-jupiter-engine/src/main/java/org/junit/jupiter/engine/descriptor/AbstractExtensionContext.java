/*
 * Copyright 2015-2025 the original author or authors.
 *
 * All rights reserved. This program and the accompanying materials are
 * made available under the terms of the Eclipse Public License v2.0 which
 * accompanies this distribution and is available at
 *
 * https://www.eclipse.org/legal/epl-v20.html
 */

package org.junit.jupiter.engine.descriptor;

import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.toCollection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.extension.ExecutableInvoker;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.MediaType;
import org.junit.jupiter.api.function.ThrowingConsumer;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.engine.config.JupiterConfiguration;
import org.junit.jupiter.engine.execution.DefaultExecutableInvoker;
import org.junit.jupiter.engine.extension.ExtensionContextInternal;
import org.junit.jupiter.engine.extension.ExtensionRegistry;
import org.junit.platform.commons.JUnitException;
import org.junit.platform.commons.logging.Logger;
import org.junit.platform.commons.logging.LoggerFactory;
import org.junit.platform.commons.util.Preconditions;
import org.junit.platform.commons.util.UnrecoverableExceptions;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.reporting.FileEntry;
import org.junit.platform.engine.reporting.ReportEntry;
import org.junit.platform.engine.support.hierarchical.Node;
import org.junit.platform.engine.support.store.NamespacedHierarchicalStore;

/**
 * @since 5.0
 */
abstract class AbstractExtensionContext<T extends TestDescriptor> implements ExtensionContextInternal, AutoCloseable {

	private static final Logger LOGGER = LoggerFactory.getLogger(AbstractExtensionContext.class);

	private final ExtensionContext parent;
	private final EngineExecutionListener engineExecutionListener;
	private final T testDescriptor;
	private final Set<String> tags;
	private final JupiterConfiguration configuration;
	private final ExecutableInvoker executableInvoker;
	private final ExtensionRegistry extensionRegistry;
	private final LauncherStoreFacade launcherStoreFacade;
	private final NamespacedHierarchicalStore<org.junit.platform.engine.support.store.Namespace> valuesStore;

	AbstractExtensionContext(ExtensionContext parent, EngineExecutionListener engineExecutionListener, T testDescriptor,
			JupiterConfiguration configuration, ExtensionRegistry extensionRegistry,
			LauncherStoreFacade launcherStoreFacade) {

		Preconditions.notNull(testDescriptor, "TestDescriptor must not be null");
		Preconditions.notNull(configuration, "JupiterConfiguration must not be null");
		Preconditions.notNull(extensionRegistry, "ExtensionRegistry must not be null");
		this.executableInvoker = new DefaultExecutableInvoker(this, extensionRegistry);
		this.parent = parent;
		this.engineExecutionListener = engineExecutionListener;
		this.testDescriptor = testDescriptor;
		this.configuration = configuration;
		this.extensionRegistry = extensionRegistry;
		this.launcherStoreFacade = launcherStoreFacade;

		// @formatter:off
		this.tags = testDescriptor.getTags().stream()
				.map(TestTag::getName)
				.collect(collectingAndThen(toCollection(LinkedHashSet::new), Collections::unmodifiableSet));
		// @formatter:on

		this.valuesStore = createStore(parent, launcherStoreFacade, createCloseAction());
	}

	@SuppressWarnings("deprecation")
	private NamespacedHierarchicalStore.CloseAction<org.junit.platform.engine.support.store.Namespace> createCloseAction() {
		return (__, ___, value) -> {
			boolean isAutoCloseEnabled = this.configuration.isAutoCloseEnabled();

			if (value instanceof AutoCloseable && isAutoCloseEnabled) {
				((AutoCloseable) value).close();
				return;
			}

			if (value instanceof Store.CloseableResource) {
				if (isAutoCloseEnabled) {
					LOGGER.warn(
						() -> "Type implements CloseableResource but not AutoCloseable: " + value.getClass().getName());
				}
				((Store.CloseableResource) value).close();
			}
		};
	}

	private static NamespacedHierarchicalStore<org.junit.platform.engine.support.store.Namespace> createStore(
			ExtensionContext parent, LauncherStoreFacade launcherStoreFacade,
			NamespacedHierarchicalStore.CloseAction<org.junit.platform.engine.support.store.Namespace> closeAction) {
		NamespacedHierarchicalStore<org.junit.platform.engine.support.store.Namespace> parentStore;
		if (parent == null) {
			parentStore = launcherStoreFacade.getRequestLevelStore();
		}
		else {
			parentStore = ((AbstractExtensionContext<?>) parent).valuesStore;
		}
		return new NamespacedHierarchicalStore<>(parentStore, closeAction);
	}

	@Override
	public void close() {
		this.valuesStore.close();
	}

	@Override
	public String getUniqueId() {
		return getTestDescriptor().getUniqueId().toString();
	}

	@Override
	public String getDisplayName() {
		return getTestDescriptor().getDisplayName();
	}

	@Override
	public void publishReportEntry(Map<String, String> values) {
		this.engineExecutionListener.reportingEntryPublished(this.testDescriptor, ReportEntry.from(values));
	}

	@Override
	public void publishFile(String name, MediaType mediaType, ThrowingConsumer<Path> action) {
		Preconditions.notNull(name, "name must not be null");
		Preconditions.notNull(mediaType, "mediaType must not be null");
		Preconditions.notNull(action, "action must not be null");

		publishFileEntry(name, action, file -> {
			Preconditions.condition(Files.isRegularFile(file), () -> "Published path must be a regular file: " + file);
			return FileEntry.from(file, mediaType.toString());
		});
	}

	@Override
	public void publishDirectory(String name, ThrowingConsumer<Path> action) {
		Preconditions.notNull(name, "name must not be null");
		Preconditions.notNull(action, "action must not be null");

		ThrowingConsumer<Path> enhancedAction = path -> {
			Files.createDirectory(path);
			action.accept(path);
		};
		publishFileEntry(name, enhancedAction, file -> {
			Preconditions.condition(Files.isDirectory(file), () -> "Published path must be a directory: " + file);
			return FileEntry.from(file, null);
		});
	}

	private void publishFileEntry(String name, ThrowingConsumer<Path> action,
			Function<Path, FileEntry> fileEntryCreator) {
		Path dir = createOutputDirectory();
		Path path = dir.resolve(name);
		Preconditions.condition(path.getParent().equals(dir), () -> "name must not contain path separators: " + name);
		try {
			action.accept(path);
		}
		catch (Throwable t) {
			UnrecoverableExceptions.rethrowIfUnrecoverable(t);
			throw new JUnitException("Failed to publish path", t);
		}
		Preconditions.condition(Files.exists(path), () -> "Published path must exist: " + path);
		FileEntry fileEntry = fileEntryCreator.apply(path);
		this.engineExecutionListener.fileEntryPublished(this.testDescriptor, fileEntry);
	}

	private Path createOutputDirectory() {
		try {
			return configuration.getOutputDirectoryProvider().createOutputDirectory(this.testDescriptor);
		}
		catch (IOException e) {
			throw new JUnitException("Failed to create output directory", e);
		}
	}

	@Override
	public Optional<ExtensionContext> getParent() {
		return Optional.ofNullable(this.parent);
	}

	@Override
	public ExtensionContext getRoot() {
		if (this.parent != null) {
			return this.parent.getRoot();
		}
		return this;
	}

	protected T getTestDescriptor() {
		return this.testDescriptor;
	}

	@Override
	public Store getStore(Namespace namespace) {
		return launcherStoreFacade.getStoreAdapter(this.valuesStore, namespace);
	}

	@Override
	public Store getStore(StoreScope scope, Namespace namespace) {
		// TODO [#4246] Use switch expression
		switch (scope) {
			case LAUNCHER_SESSION:
				return launcherStoreFacade.getSessionLevelStore(namespace);
			case EXECUTION_REQUEST:
				return launcherStoreFacade.getRequestLevelStore(namespace);
			case EXTENSION_CONTEXT:
				return getStore(namespace);
		}
		throw new JUnitException("Unknown StoreScope: " + scope);
	}

	@Override
	public Set<String> getTags() {
		// return modifiable copy
		return new LinkedHashSet<>(this.tags);
	}

	@Override
	public Optional<String> getConfigurationParameter(String key) {
		return this.configuration.getRawConfigurationParameter(key);
	}

	@Override
	public <V> Optional<V> getConfigurationParameter(String key, Function<String, V> transformer) {
		return this.configuration.getRawConfigurationParameter(key, transformer);
	}

	@Override
	public ExecutionMode getExecutionMode() {
		return toJupiterExecutionMode(getPlatformExecutionMode());
	}

	@Override
	public ExecutableInvoker getExecutableInvoker() {
		return executableInvoker;
	}

	@Override
	public <E extends Extension> List<E> getExtensions(Class<E> extensionType) {
		return extensionRegistry.getExtensions(extensionType);
	}

	protected abstract Node.ExecutionMode getPlatformExecutionMode();

	private ExecutionMode toJupiterExecutionMode(Node.ExecutionMode mode) {
		switch (mode) {
			case CONCURRENT:
				return ExecutionMode.CONCURRENT;
			case SAME_THREAD:
				return ExecutionMode.SAME_THREAD;
		}
		throw new JUnitException("Unknown ExecutionMode: " + mode);
	}
}
