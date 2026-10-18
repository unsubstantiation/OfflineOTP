/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Minimal JUnit 4 API stand-in for scripts/run-jvm-tests.sh: the annotations the reflective
 * runner looks for, the assertions the tests call and Assume for skip conditions. Only the
 * members the app's JVM test suite uses are provided; anything beyond that surface should first
 * be added to the runner, not worked around in a test. The assertions keep JUnit's message
 * formats so failures read the same under this harness and under Gradle.
 */
package org.junit

import org.junit.internal.AssumptionViolatedException

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Test

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Before

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class After

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Ignore(val value: String = "")

object Assert {
    @JvmStatic
    fun assertTrue(condition: Boolean) {
        if (!condition) fail("expected true")
    }

    @JvmStatic
    fun assertTrue(message: String, condition: Boolean) {
        if (!condition) fail(message)
    }

    @JvmStatic
    fun assertFalse(condition: Boolean) {
        if (condition) fail("expected false")
    }

    @JvmStatic
    fun assertFalse(message: String, condition: Boolean) {
        if (condition) fail(message)
    }

    @JvmStatic
    fun assertNull(value: Any?) {
        if (value != null) fail("expected null but was:<$value>")
    }

    @JvmStatic
    fun assertNull(message: String, value: Any?) {
        if (value != null) fail(message)
    }

    @JvmStatic
    fun assertNotNull(value: Any?) {
        if (value == null) fail("expected not null")
    }

    @JvmStatic
    fun assertNotNull(message: String, value: Any?) {
        if (value == null) fail(message)
    }

    @JvmStatic
    fun assertEquals(expected: Any?, actual: Any?) {
        if (!isEqual(expected, actual)) failNotEquals(expected, actual)
    }

    @JvmStatic
    fun assertEquals(message: String, expected: Any?, actual: Any?) {
        if (!isEqual(expected, actual)) fail("$message ${describe(expected, actual)}")
    }

    @JvmStatic
    fun assertEquals(expected: Int, actual: Int) {
        if (expected != actual) failNotEquals(expected, actual)
    }

    @JvmStatic
    fun assertEquals(expected: Long, actual: Long) {
        if (expected != actual) failNotEquals(expected, actual)
    }

    @JvmStatic
    fun assertEquals(expected: Boolean, actual: Boolean) {
        if (expected != actual) failNotEquals(expected, actual)
    }

    @JvmStatic
    fun assertEquals(expected: String, actual: String) {
        if (expected != actual) failNotEquals(expected, actual)
    }

    @JvmStatic
    fun assertNotEquals(expected: Any?, actual: Any?) {
        if (isEqual(expected, actual)) fail("expected not equal:<$expected>")
    }

    @JvmStatic
    fun assertNotEquals(expected: Int, actual: Int) {
        if (expected == actual) fail("expected not equal:<$expected>")
    }

    @JvmStatic
    fun assertNotEquals(expected: Long, actual: Long) {
        if (expected == actual) fail("expected not equal:<$expected>")
    }

    @JvmStatic
    fun assertArrayEquals(expected: ByteArray?, actual: ByteArray?) {
        if (!expected.contentEquals(actual)) {
            fail("expected:<${expected?.contentToString()}> but was:<${actual?.contentToString()}>")
        }
    }

    /** JUnit 4.13's assertThrows shape: run the block, expect the given exception type. */
    @JvmStatic
    fun <T : Throwable> assertThrows(expected: Class<T>, executable: () -> Unit): T {
        try {
            executable()
        } catch (thrown: Throwable) {
            if (expected.isInstance(thrown)) {
                @Suppress("UNCHECKED_CAST")
                return thrown as T
            }
            throw AssertionError("Unexpected exception type, expected ${expected.name} " +
                "but was ${thrown.javaClass.name}", thrown)
        }
        throw AssertionError("Expected ${expected.name} but nothing was thrown")
    }

    @JvmStatic
    fun fail(message: String?): Nothing {
        throw AssertionError(message ?: "fail")
    }

    private fun isEqual(expected: Any?, actual: Any?): Boolean = expected == actual

    private fun describe(expected: Any?, actual: Any?): String =
        "expected:<$expected> but was:<$actual>"

    private fun failNotEquals(expected: Any?, actual: Any?): Nothing =
        throw AssertionError(describe(expected, actual))
}

object Assume {
    @JvmStatic
    fun assumeTrue(condition: Boolean) {
        if (!condition) throw AssumptionViolatedException("assumption failed")
    }

    @JvmStatic
    fun assumeTrue(message: String, condition: Boolean) {
        if (!condition) throw AssumptionViolatedException(message)
    }

    @JvmStatic
    fun assumeFalse(condition: Boolean) {
        if (condition) throw AssumptionViolatedException("assumption failed")
    }

    @JvmStatic
    fun assumeFalse(message: String, condition: Boolean) {
        if (condition) throw AssumptionViolatedException(message)
    }
}
