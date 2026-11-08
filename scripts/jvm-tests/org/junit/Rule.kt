package org.junit

// Compile-only annotation for instrumentation tests; the sandbox runner does not execute rules.
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Rule
