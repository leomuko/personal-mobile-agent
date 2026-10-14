package dev.edgecompanion.core.inference

import kotlin.test.*

class ChatGenerationTest {
    @Test fun rejectsOversizedCurrentInputAndMalformedUnicode() {
        assertFailsWith<ModelException> { GemmaChatBudget.build("x".repeat(500), emptyList(), "id") }
        assertFailsWith<ModelException> { GemmaChatBudget.build("\uD800", emptyList(), "id") }
        assertFailsWith<ModelException> { GemmaChatBudget.build("<|image|>", emptyList(), "id") }
        val request = GemmaChatBudget.build("Hello \u65e5\u672c \uD83D\uDE42", emptyList(), "id")
        assertTrue(GemmaChatBudget.validate(request) + request.outputTokens <= 2048)
    }
    @Test fun trimsWholeOlderTurnsAndAccountsForEveryRole() {
        val turns = listOf("x".repeat(500) to "old", "Hi" to "Hello")
        val request = GemmaChatBudget.build("What next?", turns, "id")
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER), request.messages.map { it.role })
        assertEquals("Hi", request.messages.first().text)
        assertEquals("What next?", request.messages.last().text)
        assertFailsWith<IllegalArgumentException> {
            GemmaChatBudget.inputBound(listOf(ChatMessage(ChatRole.ASSISTANT, "invalid")))
        }
    }
    @Test fun exactBoundIncludesOutputReservation() {
        val text = "x".repeat(437)
        assertEquals(1535, GemmaChatBudget.validate(ChatRequest("id", listOf(ChatMessage(ChatRole.USER, text)))))
        assertFailsWith<ModelException> { GemmaChatBudget.validate(ChatRequest("id", listOf(ChatMessage(ChatRole.USER, text + "x")))) }
    }
}
