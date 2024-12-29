/*
 * Copyright 2015-2024 the original author or authors.
 *
 * All rights reserved. This program and the accompanying materials are
 * made available under the terms of the Eclipse Public License v2.0 which
 * accompanies this distribution and is available at
 *
 * https://www.eclipse.org/legal/epl-v20.html
 */

package org.junit.platform.launcher;

/**
 * Interface for controlling the execution of tests.
 */
public interface TestExecutionControl {

	/**
	 * Cancels the ongoing test execution.
	 *
	 * <p>This method initiates a graceful shutdown of the test execution process.
	 * It allows currently running tests to complete but prevents any new tests
	 * from starting.
	 */
	void cancel();

	/**
	 * Forcefully terminates the test execution.
	 *
	 * <p>This method immediately stops all running tests and terminates the
	 * test execution process. It should be used with caution as it may leave
	 * the system in an inconsistent state.
	 */
	void forceTerminate();

	/**
	 * Sets and returns the failure threshold for stopping test execution.
	 *
	 * <p>This method sets the number of test failures after which the remaining test execution
	 * will be automatically stopped. Setting this to a positive number will stop the test execution
	 * after the specified number of failures has been encountered. This is particularly
	 * useful for handling potentially flaky tests or for optimizing test suite execution time.
	 *
	 * <p>For example, if you suspect that some tests in your suite might be flaky,
	 * calling {@code failureThreshold(1)} would stop the execution after the first
	 * failure, as this is sufficient to indicate a potential issue. Alternatively,
	 * you can set the threshold to a number greater than 1 depending on your specific
	 * testing requirements and tolerance for failures.
	 *
	 * <p>Setting this to {@link Integer#MAX_VALUE} signals that no failure
	 * threshold will be applied, meaning that all tests in the suite will be executed
	 * regardless of how many failures occur.
	 *
	 * @param failureThreshold the failure threshold to be set
	 * @return the set failure threshold
	 */
	int failureThreshold(int failureThreshold);

}
