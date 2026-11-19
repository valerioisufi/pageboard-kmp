package io.github.valerioisufi.pageboard.store.room

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A driver counting the statements run on the connections of [driver] (the queries apart) and the
 * time they take: the benchmarks of the store (see `docs/plans/performance.md`).
 */
internal class CountingSQLiteDriver(private val driver: SQLiteDriver) : SQLiteDriver {
    private val queryCount = AtomicInteger()
    private val otherCount = AtomicInteger()
    private val queryNanos = AtomicLong()
    private val otherNanos = AtomicLong()
    private val runs = ConcurrentHashMap<String, AtomicInteger>()

    /** The queries run (`SELECT`) since the last [reset]. */
    val queries: Int get() = queryCount.get()

    /** The other statements run (writes, transactions, pragmas) since the last [reset]. */
    val others: Int get() = otherCount.get()

    val statements: Int get() = queries + others

    /** The time spent in the queries since the last [reset], in milliseconds. */
    val queryMillis: Double get() = queryNanos.get() / 1e6

    /** The time spent in the other statements since the last [reset], in milliseconds. */
    val otherMillis: Double get() = otherNanos.get() / 1e6

    /** How many times the statements whose SQL contains [fragment] ran since the last [reset]. */
    fun runsOf(fragment: String): Int = runs.entries.filter { fragment in it.key }.sumOf { it.value.get() }

    fun reset() {
        runs.clear()
        queryCount.set(0)
        otherCount.set(0)
        queryNanos.set(0)
        otherNanos.set(0)
    }

    override val hasConnectionPool: Boolean get() = driver.hasConnectionPool

    override fun open(fileName: String): SQLiteConnection = CountingConnection(driver.open(fileName))

    private inner class CountingConnection(private val connection: SQLiteConnection) : SQLiteConnection by connection {
        override fun prepare(sql: String): SQLiteStatement {
            val query = sql.trimStart().let { it.startsWith("SELECT", ignoreCase = true) || it.startsWith("WITH", ignoreCase = true) }
            return CountingStatement(connection.prepare(sql), sql, query)
        }
    }

    /** A statement counted once per run: from its first step to its reset. */
    private inner class CountingStatement(private val statement: SQLiteStatement, private val sql: String, private val query: Boolean) : SQLiteStatement by statement {
        private var counted = false

        override fun step(): Boolean {
            if (!counted) {
                counted = true
                (if (query) queryCount else otherCount).incrementAndGet()
                runs.getOrPut(sql) { AtomicInteger() }.incrementAndGet()
            }
            val start = System.nanoTime()
            try {
                return statement.step()
            } finally {
                (if (query) queryNanos else otherNanos).addAndGet(System.nanoTime() - start)
            }
        }

        override fun reset() {
            counted = false
            statement.reset()
        }
    }
}
