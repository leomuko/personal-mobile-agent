package dev.edgecompanion.core.inference.litert

import com.google.gson.JsonParser
import dev.edgecompanion.core.inference.verification.*
import java.io.File
import java.security.MessageDigest
import kotlin.test.*

/** Host structural fixtures only; no Engine, Android, model or native runtime. */
class GrammarProgressFixturesTest {
    @Test fun frozenCompactWitnessesPreserveExactQuotesAndStrictRejections() {
        val folder = File("../planning/fixtures/grammar-progress-v1")
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val lockFile = File(folder, "preparation-lock.json")
        assertEquals("ac08cdab90f90b959681002270114f35d4e5bfdfa94b89aa167d5b334a9a6749", hash(lockFile.readBytes()))
        val lock = JsonParser.parseString(lockFile.readText()).asJsonObject
        for ((name, expected) in lock["files"].asJsonObject.entrySet()) {
            assertEquals(expected.asString, hash(File(folder, name).readBytes()), name)
        }
        val rows = JsonParser.parseString(File(folder, "witnesses.json").readText()).asJsonObject["witnesses"].asJsonArray
        assertEquals(14, rows.size())
        for (row in rows) {
            val w = row.asJsonObject
            val c = w["context"].asJsonObject
            val context = EvidenceContext(c["detailIds"].asJsonArray.map { it.asString }, c["passages"].asJsonArray.map {
                val p = it.asJsonObject
                DeliveredPassage(p["p"].asString, p["r"].asString, p["original"].asString, p["b"].asInt, p["e"].asInt)
            })
            val result = EvidenceOutputValidator.validate(w["output"].asString, context, w["outputTokens"].asInt)
            assertEquals(w["strictAccepted"].asBoolean, result is OutputValidation.StructurallyValid, w["id"].asString)
            if (result is OutputValidation.StructurallyValid) {
                for (detail in result.output.details) for (span in detail.evidence) {
                    val original = c["passages"].asJsonArray.single { it.asJsonObject["p"].asString == span.p }.asJsonObject["original"].asString
                    assertEquals(original.substring(span.b, span.e), span.q)
                }
            }
        }
    }
}
