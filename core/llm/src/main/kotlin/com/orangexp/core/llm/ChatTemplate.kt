package com.orangexp.core.llm

enum class Role { USER, MODEL }

data class Turn(val role: Role, val text: String)

/** One request to the model: instructions and facts, earlier turns, and the new message. */
data class ChatPrompt(
    val system: String,
    val history: List<Turn>,
    val message: String,
)

/**
 * Each model family expects its own turn markers. The runtime's automatic
 * templating is switched off and the prompt is formatted here, so it is the
 * same whatever metadata a model file carries.
 */
enum class ChatTemplate {
    GEMMA,
    CHATML,
    PHI,
    LLAMA3,
    PLAIN,
    ;

    fun format(prompt: ChatPrompt): String = buildString {
        when (this@ChatTemplate) {
            GEMMA -> {
                // Gemma has no system role: instructions open the first user turn.
                val turns = prompt.history + Turn(Role.USER, prompt.message)
                turns.forEachIndexed { i, turn ->
                    val role = if (turn.role == Role.USER) "user" else "model"
                    val text = if (i == 0) "${prompt.system}\n\n${turn.text}" else turn.text
                    append("<start_of_turn>").append(role).append('\n').append(text).append("<end_of_turn>\n")
                }
                append("<start_of_turn>model\n")
            }
            CHATML -> {
                append("<|im_start|>system\n").append(prompt.system).append("<|im_end|>\n")
                for (turn in prompt.history + Turn(Role.USER, prompt.message)) {
                    val role = if (turn.role == Role.USER) "user" else "assistant"
                    append("<|im_start|>").append(role).append('\n').append(turn.text).append("<|im_end|>\n")
                }
                append("<|im_start|>assistant\n")
            }
            PHI -> {
                append("<|system|>").append(prompt.system).append("<|end|>")
                for (turn in prompt.history + Turn(Role.USER, prompt.message)) {
                    append(if (turn.role == Role.USER) "<|user|>" else "<|assistant|>").append(turn.text).append("<|end|>")
                }
                append("<|assistant|>")
            }
            LLAMA3 -> {
                append("<|start_header_id|>system<|end_header_id|>\n\n").append(prompt.system).append("<|eot_id|>")
                for (turn in prompt.history + Turn(Role.USER, prompt.message)) {
                    val role = if (turn.role == Role.USER) "user" else "assistant"
                    append("<|start_header_id|>").append(role).append("<|end_header_id|>\n\n").append(turn.text).append("<|eot_id|>")
                }
                append("<|start_header_id|>assistant<|end_header_id|>\n\n")
            }
            PLAIN -> {
                append(prompt.system).append("\n\n")
                for (turn in prompt.history + Turn(Role.USER, prompt.message)) {
                    append(if (turn.role == Role.USER) "User: " else "Assistant: ").append(turn.text).append('\n')
                }
                append("Assistant:")
            }
        }
    }

    /** Markers that end a turn; some models print them, and they are never shown. */
    val stopMarkers: List<String>
        get() = when (this) {
            GEMMA -> listOf("<end_of_turn>", "<start_of_turn>")
            CHATML -> listOf("<|im_end|>", "<|im_start|>", "<|endoftext|>")
            PHI -> listOf("<|end|>", "<|user|>", "<|endoftext|>")
            LLAMA3 -> listOf("<|eot_id|>", "<|start_header_id|>", "<|end_of_text|>")
            PLAIN -> listOf("\nUser:")
        }

    companion object {
        /** Guesses the family from the file name, e.g. `gemma-3n-E2B-it-int4.task`. */
        fun forModelFile(fileName: String): ChatTemplate {
            val name = fileName.lowercase()
            return when {
                "gemma" in name -> GEMMA
                "qwen" in name || "smollm" in name || "deepseek" in name -> CHATML
                "phi" in name -> PHI
                "llama" in name -> LLAMA3
                else -> GEMMA
            }
        }
    }
}
