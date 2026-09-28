package dev.edgecompanion.core.storage.database

import app.cash.sqldelight.db.SqlDriver
import dev.edgecompanion.db.CompanionDatabase

/** Constructing the graph does no I/O; only session startup resolves the database. */
internal class SessionDatabase(private val factory: DatabaseDriverFactory) {
    private var driver: SqlDriver? = null
    val database: CompanionDatabase by lazy {
        val opened = factory.open()
        try {
            CompanionDatabase(opened).also { driver = opened }
        } catch (error: Exception) {
            opened.close()
            throw error
        }
    }
    fun close() { driver?.close(); driver = null }
}

