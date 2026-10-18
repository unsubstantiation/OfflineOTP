/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Part of the minimal JUnit 4 stand-in for scripts/run-jvm-tests.sh. The instrumentation
 * sources use @RunWith(AndroidJUnit4::class); the host suite does not need a runner (the
 * reflective JvmTestRunner plays that role), but compiling both suites against one shim keeps
 * the API surface honest.
 */
package org.junit.runner

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RunWith(val value: KClass<*>)
