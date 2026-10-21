package dev.edgecompanion.core.inference

import kotlin.test.*

class ChatGenerationTest {
    @Test fun rejectsOversizedCurrentInputAndMalformedUnicode() {
        assertFailsWith<ModelException> { GemmaChatBudget.build("x".repeat(1313), emptyList(), "id") }
        assertFailsWith<ModelException> { GemmaChatBudget.build("\uD800", emptyList(), "id") }
        assertFailsWith<ModelException> { GemmaChatBudget.build("<|image|>", emptyList(), "id") }
        val request = GemmaChatBudget.build("Hello \u65e5\u672c \uD83D\uDE42", emptyList(), "id")
        assertTrue(GemmaChatBudget.validate(request) + request.outputTokens <= 2048)
    }
    @Test fun trimsWholeOlderTurnsAndAccountsForEveryRole() {
        val turns = listOf("x".repeat(1313) to "old", "Hi" to "Hello")
        val request = GemmaChatBudget.build("What next?", turns, "id")
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER), request.messages.map { it.role })
        assertEquals("Hi", request.messages.first().text)
        assertEquals("What next?", request.messages.last().text)
        assertFailsWith<IllegalArgumentException> {
            GemmaChatBudget.inputBound(listOf(ChatMessage(ChatRole.ASSISTANT, "invalid")))
        }
    }
    @Test fun exactBoundIncludesOutputReservation() {
        val text = "x".repeat(1312)
        assertEquals(1536, GemmaChatBudget.validate(ChatRequest("id", listOf(ChatMessage(ChatRole.USER, text)))))
        assertFailsWith<ModelException> { GemmaChatBudget.validate(ChatRequest("id", listOf(ChatMessage(ChatRole.USER, text + "x")))) }
    }
    @Test fun usefulEvidenceAndFollowupFitWithoutChangingContext() {
        val evidence = (1..4).joinToString("\n") { "[s$it] Synthetic pilot notes: launch on 6 October; John owns the contract." }
        val current = "Use only the supplied evidence and cite its labels.\n$evidence\nWhen is the launch?"
        val request = GemmaChatBudget.build(current, listOf("What is this project?" to "A community pilot."), "proof")
        assertEquals(3, request.messages.size)
        assertEquals(2048, request.contextTokens)
        assertEquals(512, request.outputTokens)
        assertTrue(GemmaChatBudget.validate(request) + 512 <= 2048)
    }
    @Test fun whitespaceAndUnicodeUseUtf8CostWithoutDroppingCurrentQuestion() {
        val text = " \t\n\u2581\u65e5\uD83D\uDE42e\u0301 "
        assertEquals(224L + text.encodeToByteArray().size, GemmaChatBudget.inputBound(listOf(ChatMessage(ChatRole.USER, text))))
        assertEquals(text, GemmaChatBudget.build(text, emptyList(), "id").messages.last().text)
    }
}
