package dev.edgecompanion.app.support

/** JSON representation only: preserve every UTF-16 unit if a cancelled callback ends mid-scalar. */
fun escapeQaJsonSurrogates(serializedJson: String): String = buildString {
    for (c in serializedJson) {
        if (c.isSurrogate()) append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
    }
}
