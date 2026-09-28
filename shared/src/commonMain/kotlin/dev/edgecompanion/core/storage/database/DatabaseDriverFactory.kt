package dev.edgecompanion.core.storage.database

import app.cash.sqldelight.db.SqlDriver

fun interface DatabaseDriverFactory { fun open(): SqlDriver }
