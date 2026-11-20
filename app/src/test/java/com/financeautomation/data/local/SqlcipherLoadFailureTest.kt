package com.financeautomation.data.local

import android.content.ContextWrapper
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.InvocationTargetException

/**
 * The JVM has no native sqlcipher library. The class must still load for unit tests, but opening
 * the real database must fail loudly with the load error instead of hiding it.
 */
class SqlcipherLoadFailureTest {

    @Test
    fun openingTheRealDatabaseThrowsTheLoadErrorWhenSqlcipherIsMissing() {
        val companion = FinanceDatabase.Companion
        val method = companion.javaClass.getDeclaredMethod("buildDatabase", android.content.Context::class.java)
        method.isAccessible = true
        try {
            method.invoke(companion, ContextWrapper(null))
            fail("Expected the sqlcipher load error to be thrown")
        } catch (e: InvocationTargetException) {
            assertTrue(
                "Expected UnsatisfiedLinkError but got ${e.cause}",
                e.cause is UnsatisfiedLinkError
            )
        }
    }
}
