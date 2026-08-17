package org.tekfive.polyglot

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

fun interface ToolExecutor {
    suspend fun execute(call: ContentPart.ToolCall): ToolExecutionResult
}

data class ToolExecutionResult(
    val value: JsonElement = JsonPrimitive("OK"),
    val isError: Boolean = false,
)

class Conversation(
    private val client: PolyglotClient,
    private val target: ModelTarget,
    private val fallbackTargets: List<ModelTarget> = emptyList(),
    private val systemMessage: String? = null,
    private val options: GenerationOptions = GenerationOptions(),
    private val tools: List<ToolDefinition> = emptyList(),
    private val toolChoice: ToolChoice? = null,
    private val toolExecutor: ToolExecutor? = null,
    private val maxToolRounds: Int = 8,
) {
    private val mutex = Mutex()
    private val history = mutableListOf<Message>()

    init {
        require(maxToolRounds > 0) { "maxToolRounds must be positive" }
    }

    suspend fun say(text: String): ChatResponse = say(listOf(ContentPart.Text(text)))

    suspend fun say(content: List<ContentPart>): ChatResponse = mutex.withLock {
        val workingHistory = history.toMutableList()
        workingHistory += Message(MessageRole.USER, content)
        var rounds = 0
        lateinit var finalResponse: ChatResponse

        while (true) {
            val response = client.complete(buildRequest(workingHistory))
            workingHistory += Message(MessageRole.ASSISTANT, response.content)
            if (response.toolCalls.isEmpty() || toolExecutor == null) {
                finalResponse = response
                break
            }

            if (++rounds > maxToolRounds) {
                throw PolyglotException("Exceeded maximum tool rounds ($maxToolRounds)")
            }

            response.toolCalls.forEach { call ->
                require(tools.any { it.name == call.name }) { "Model requested undeclared tool '${call.name}'" }
                val result = toolExecutor.execute(call)
                workingHistory += Message(
                    MessageRole.TOOL,
                    listOf(ContentPart.ToolResult(call.id, call.name, result.value, result.isError)),
                )
            }
        }

        history.clear()
        history.addAll(workingHistory)
        finalResponse
    }

    suspend fun messages(): List<Message> = mutex.withLock { history.toList() }

    suspend fun add(message: Message) = mutex.withLock { history += message }

    suspend fun clear() = mutex.withLock { history.clear() }

    private fun buildRequest(messages: List<Message>) = ChatRequest(
        target = target,
        fallbackTargets = fallbackTargets,
        messages = buildList {
            systemMessage?.let { add(Message.system(it)) }
            addAll(messages)
        },
        options = options,
        tools = tools,
        toolChoice = toolChoice,
    )
}
