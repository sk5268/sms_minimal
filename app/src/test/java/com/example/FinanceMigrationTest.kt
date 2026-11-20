package com.example

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.example.finance.FinanceDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Opens the bundled v2 finance.db through every migration up to the current schema. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FinanceMigrationTest {

    @Test
    fun bundledDatabaseMigratesAndBackfillsPayees() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dao = FinanceDatabase.getInstance(context).financeDao()
        val debits = dao.allDebits()
        assertEquals(51, debits.size)
        val bankDebits = debits.filter { !it.messageKey.startsWith("manual:") }
        val withPayee = bankDebits.count { it.payeeKey != null }
        println("payee coverage: $withPayee / ${bankDebits.size}")
        assertTrue("payee coverage too low: $withPayee/${bankDebits.size}", withPayee * 10 >= bankDebits.size * 8)
        assertTrue(debits.all { it.tagSource in setOf("manual", "none", "legacy_auto") })
        debits.filter { it.payeeKey == null && !it.messageKey.startsWith("manual:") }
            .forEach { println("no payee: ${it.snippet.take(120)}") }
    }
}
