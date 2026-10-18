/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Part of the minimal JUnit 4 stand-in for scripts/run-jvm-tests.sh. The verification
 * environment cannot fetch the junit jars (no GitHub release assets, no Maven Central access
 * beyond the mirrors the sandbox bootstrap uses), so the API surface the app's JVM tests use is
 * reproduced here. On a host with Gradle the same test sources compile and run unchanged against
 * the real junit:junit:4.13.2 declared in app/build.gradle.
 */
package org.junit.internal

/** Thrown by Assume to mark a test as skipped rather than failed. */
class AssumptionViolatedException(message: String?) : RuntimeException(message)
