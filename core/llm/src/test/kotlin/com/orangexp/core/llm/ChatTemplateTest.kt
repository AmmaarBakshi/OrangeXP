package com.orangexp.core.llm

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatTemplateTest {

    private val prompt = ChatPrompt(
        system = "You are Holstrom.",
        history = listOf(Turn(Role.USER, "hi"), Turn(Role.MODEL, "Hello.")),
        message = "how did I sleep?",
    )

    @Test
    fun `gemma puts instructions in the first user turn and ends open for the model`() {
        assertEquals(
            "<start_of_turn>user\nYou are Holstrom.\n\nhi<end_of_turn>\n" +
                "<start_of_turn>model\nHello.<end_of_turn>\n" +
                "<start_of_turn>user\nhow did I sleep?<end_of_turn>\n" +
                "<start_of_turn>model\n",
            ChatTemplate.GEMMA.format(prompt),
        )
    }

    @Test
    fun `chatml has a system turn`() {
        val text = ChatTemplate.CHATML.format(prompt)
        assertEquals(true, text.startsWith("<|im_start|>system\nYou are Holstrom.<|im_end|>\n"))
        assertEquals(true, text.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun `family is recognised from the file name`() {
        assertEquals(ChatTemplate.GEMMA, ChatTemplate.forModelFile("gemma-3n-E2B-it-int4.task"))
        assertEquals(ChatTemplate.CHATML, ChatTemplate.forModelFile("Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"))
        assertEquals(ChatTemplate.PHI, ChatTemplate.forModelFile("Phi-4-mini-instruct.task"))
        assertEquals(ChatTemplate.LLAMA3, ChatTemplate.forModelFile("Llama-3.2-1B-Instruct.task"))
        assertEquals(ChatTemplate.GEMMA, ChatTemplate.forModelFile("model.task"))
    }
}
