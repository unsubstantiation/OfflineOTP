/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Reflective runner for the JVM test suite. Scans the compiled output for classes named *Test,
 * then runs every @Test method on a fresh instance with @Before/@Before-like setup, mirroring
 * JUnit 4's per-method instance semantics. @Ignore skips, an AssumptionViolatedException from
 * Assume skips, anything else fails the run (non-zero exit).
 *
 * The exit code is what CI consumes: 0 = all green, 1 = at least one failure.
 */
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.size != 1) {
        System.err.println("usage: JvmTestRunnerKt <compiled-classes-directory>")
        exitProcess(2)
    }
    val base = File(args[0])
    if (!base.isDirectory) {
        System.err.println("not a directory: $base")
        exitProcess(2)
    }

    val classNames = base.walkTopDown()
        .filter { it.isFile && it.name.endsWith(".class") && !it.name.contains('$') }
        .map { it.relativeTo(base).invariantSeparatorsPath.removeSuffix(".class").replace('/', '.') }
        .filter { it.endsWith("Test") }
        .sorted()
        .toList()

    var passed = 0
    var failed = 0
    var skipped = 0

    for (name in classNames) {
        val clazz = try {
            Class.forName(name)
        } catch (unavailable: Throwable) {
            failed++
            println("FAIL  $name (cannot load: $unavailable)")
            continue
        }
        if (Modifier.isAbstract(clazz.modifiers)) continue

        val testMethods = clazz.declaredMethods
            .filter { it.isAnnotationPresent(Test::class.java) }
            .sortedBy { it.name }
        if (testMethods.isEmpty()) continue

        for (method in testMethods) {
            if (method.isAnnotationPresent(Ignore::class.java)) {
                skipped++
                println("SKIP  $name.${method.name}")
                continue
            }
            val instance = try {
                clazz.getDeclaredConstructor().newInstance()
            } catch (uninstantiable: Throwable) {
                failed++
                println("FAIL  $name.${method.name} (no usable no-arg constructor: $uninstantiable)")
                continue
            }
            val label = "$name.${method.name}"
            try {
                runAnnotated(clazz, instance, Before::class.java)
                method.isAccessible = true
                method.invoke(instance)
                passed++
                println("PASS  $label")
            } catch (failure: InvocationTargetException) {
                val cause = failure.cause ?: failure
                if (cause is org.junit.internal.AssumptionViolatedException) {
                    skipped++
                    println("SKIP  $label (assumption)")
                } else {
                    failed++
                    println("FAIL  $label")
                    cause.printStackTrace(System.out)
                }
            } catch (failure: Throwable) {
                failed++
                println("FAIL  $label")
                failure.printStackTrace(System.out)
            } finally {
                try {
                    runAnnotated(clazz, instance, After::class.java)
                } catch (cleanupFailure: Throwable) {
                    failed++
                    println("FAIL  $label (@After raised)")
                    cleanupFailure.printStackTrace(System.out)
                }
            }
        }
    }

    println()
    println("Tests: ${passed + failed + skipped}, passed: $passed, failed: $failed, skipped: $skipped")
    if (classNames.isEmpty()) {
        System.err.println("no test classes found under $base")
        exitProcess(2)
    }
    exitProcess(if (failed == 0) 0 else 1)
}

private fun runAnnotated(clazz: Class<*>, instance: Any, annotation: Class<out Annotation>) {
    for (method in clazz.declaredMethods.filter { it.isAnnotationPresent(annotation) }.sortedBy { it.name }) {
        method.isAccessible = true
        method.invoke(instance)
    }
}
