package dev.edgecompanion.core.inference.litert

import com.google.gson.*
import dev.edgecompanion.core.inference.*
import dev.edgecompanion.core.inference.verification.*
import java.io.File
import java.security.MessageDigest
import kotlin.test.*

class ConstrainedFixturesTest {
    private val folder = File("../planning/fixtures/constrained-decoding-v2")
    private fun read(file: String) = JsonParser.parseString(File(folder, file).readText()).asJsonObject
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun allExactCasesAdmitAndOnlyCd07GetsOneTokenCap() {
        val prepared = JsonParser.parseString(File("../planning/fixtures/output-adherence-v1/prepared.json").readText()).asJsonObject
        for (entry in read("cases.json")["cases"].asJsonArray) {
            val c = entry.asJsonObject; val id = c["id"].asString
            var input = c["input"].asJsonObject
            if (input.has("sourceControl")) input = prepared["controls"].asJsonArray.single {
                it.asJsonObject["id"].asString == input["sourceControl"].asString
            }.asJsonObject["input"].asJsonObject
            val receipt = ConstrainedDecoding.admit(id, "synthetic-$id", input["payload"].asString, input["question"].asString,
                File(folder, c["constraint"].asString).readText(), ::hash)
            assertEquals(c["decodeTokenCap"].asInt, receipt.decodeTokenCap)
            receipt.check(ChatRequest(receipt.generationId, listOf(ChatMessage(ChatRole.USER, receipt.payload))))
            assertEquals(512, ChatRequest(receipt.generationId, listOf(ChatMessage(ChatRole.USER, receipt.payload))).outputTokens)
            assertFails { receipt.check(ChatRequest("different-request", listOf(ChatMessage(ChatRole.USER, receipt.payload)))) }
            assertFails { receipt.check(ChatRequest(receipt.generationId, listOf(ChatMessage(ChatRole.USER, receipt.payload)), outputTokens = 1)) }
            assertFails { ConstrainedDecoding.admit(id, "synthetic-$id", receipt.payload + " ", receipt.question, receipt.schema, ::hash) }
            assertFails { ConstrainedDecoding.admit(id, "synthetic-$id", receipt.payload, receipt.question, receipt.schema + " ", ::hash) }
        }
    }

    @Test fun allFrozenStrictChecksReplayWithoutSchemaBasedRepair() {
        val checks = read("host-keyword-checks.json")
        val context = EvidenceContext(listOf("a"), listOf(DeliveredPassage("sentinel-p", "1", "X", 0, 1)))
        val base = checks["baseOutput"].asJsonObject
        assertIs<OutputValidation.StructurallyValid>(EvidenceOutputValidator.validate(base.toString(), context, 128))
        val cases = checks["cases"].asJsonArray; assertEquals(34, cases.size())
        for (element in cases) {
            val c = element.asJsonObject
            val raw = if (c.has("rawOutput")) c["rawOutput"].asString else {
                var value: JsonElement = base.deepCopy()
                val mutation = c["mutation"].asJsonObject
                val path = mutation["path"].asString
                if (path.isEmpty()) value = mutation["value"].deepCopy()
                else {
                    val keys = path.drop(1).split('/').map { it.replace("~1", "/").replace("~0", "~") }
                    var target = value
                    for (key in keys.dropLast(1)) target = if (target.isJsonArray) target.asJsonArray[key.toInt()] else target.asJsonObject[key]
                    val key = keys.last()
                    if (mutation["op"].asString == "remove") target.asJsonObject.remove(key)
                    else if (target.isJsonArray) target.asJsonArray.set(key.toInt(), mutation["value"].deepCopy())
                    else target.asJsonObject.add(key, mutation["value"].deepCopy())
                }
                value.toString()
            }
            assertEquals(c["expected"].asJsonObject["strictAccepted"].asBoolean,
                EvidenceOutputValidator.validate(raw, context, 128) is OutputValidation.StructurallyValid, c["id"].asString)
        }
    }
}
