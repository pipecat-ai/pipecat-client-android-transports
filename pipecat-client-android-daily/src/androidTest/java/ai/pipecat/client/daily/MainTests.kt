package ai.pipecat.client.daily

import ai.pipecat.client.RTVIClient
import ai.pipecat.client.RTVIClientOptions
import ai.pipecat.client.RTVIClientParams
import ai.pipecat.client.RTVIEventCallbacks
import ai.pipecat.client.helper.LLMContext
import ai.pipecat.client.helper.LLMContextMessage
import ai.pipecat.client.helper.LLMHelper
import ai.pipecat.client.result.RTVIError
import ai.pipecat.client.result.Result
import ai.pipecat.client.types.ActionDescription
import ai.pipecat.client.types.Config
import ai.pipecat.client.types.Option
import ai.pipecat.client.types.OptionDescription
import ai.pipecat.client.types.ServiceConfig
import ai.pipecat.client.types.ServiceConfigDescription
import ai.pipecat.client.types.ServiceRegistration
import ai.pipecat.client.types.TransportState
import ai.pipecat.client.types.Type
import ai.pipecat.client.types.Value
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test

@LargeTest
class MainTests {

    companion object {
        private val options = RTVIClientOptions(
            services = listOf(
                ServiceRegistration("tts", "cartesia"),
                ServiceRegistration("llm", "together"),
            ),
            params = RTVIClientParams(
                baseUrl = testUrl,
                config = listOf(
                    ServiceConfig(
                        "tts", listOf(
                            Option("voice", "79a125e8-cd45-4c13-8a67-188112f4dd22")
                        )
                    ),
                    ServiceConfig(
                        "llm", listOf(
                            Option("model", "meta-llama/Meta-Llama-3.1-8B-Instruct-Turbo"),
                            Option(
                                "initial_messages", Value.Array(
                                    Value.Object(
                                        "role" to Value.Str("system"),
                                        "content" to Value.Str("You are a helpful voice assistant.")
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )

        private class TestContext(
            val client: RTVIClient,
            val llmHelper: LLMHelper
        )

        private fun runTestWithConnectedClient(
            test: suspend TestContext.() -> Unit
        ) = runBlocking(Dispatchers.Main) {
            val context = InstrumentationRegistry.getInstrumentation().context

            val client = RTVIClient(
                transport = DailyTransport.Factory(context),
                callbacks = object : RTVIEventCallbacks() {
                    override fun onBackendError(message: String) {
                        throw Exception("onBackendError: $message")
                    }
                },
                options = options
            )

            val llmHelper = client.registerHelper("llm", LLMHelper(object : LLMHelper.Callbacks() {}))

            client.connect().await()

            Assert.assertEquals(TransportState.Ready, client.state)

            TestContext(client, llmHelper).test()

            client.disconnect().await()
            client.release()
        }
    }

    @Test
    fun testDescribeActions() = runTestWithConnectedClient {
        Assert.assertEquals(
            setOf(
                ActionDescription(
                    service = "llm",
                    action = "run",
                    arguments = listOf(OptionDescription("interrupt", Type.Bool)),
                    result = Type.Bool
                ),
                ActionDescription(
                    service = "llm",
                    action = "get_context",
                    arguments = emptyList(),
                    result = Type.Array
                ),
                ActionDescription(
                    service = "llm", action = "append_to_messages", arguments = listOf(
                        OptionDescription(name = "messages", type = Type.Array),
                        OptionDescription(name = "run_immediately", type = Type.Bool)
                    ), result = Type.Bool
                ),
                ActionDescription(
                    service = "llm",
                    action = "set_context",
                    arguments = listOf(OptionDescription(name = "messages", type = Type.Array)),
                    result = Type.Bool
                ),
                ActionDescription(
                    service = "tts",
                    action = "say",
                    arguments = listOf(
                        OptionDescription(name = "text", type = Type.Str),
                    ),
                    result = Type.Bool
                ),
                ActionDescription(
                    service = "tts",
                    action = "interrupt",
                    arguments = emptyList(),
                    result = Type.Bool
                )
            ),
            client.describeActions().await().toSet()
        )
    }

    @Test
    fun testActionSay() = runTestWithConnectedClient {
        Assert.assertEquals(
            Value.Bool(true),
            client.action(
                "tts",
                "say",
                listOf(Option("text", Value.Str("Hello world")))
            ).await()
        )
    }

    @Test
    fun testActionInvalid() = runTestWithConnectedClient {
        Assert.assertEquals(
            Result.Err(RTVIError.ErrorResponse(message = "Action abc123:say not registered")),
            client.action(
                "abc123",
                "say",
                listOf(Option("text", Value.Str("Hello world")))
            ).awaitNoThrow()
        )
    }

    @Test
    fun testGetConfig() = runTestWithConnectedClient {
        Assert.assertEquals(
            Config(config = options.params.config),
            client.getConfig().await()
        )
    }

    @Test
    fun testDescribeConfig() = runTestWithConnectedClient {
        Assert.assertEquals(
            listOf(
                ServiceConfigDescription(
                    name = "llm",
                    options = listOf(
                        OptionDescription(name = "model", type = Type.Str),
                        OptionDescription(name = "initial_messages", type = Type.Array),
                        OptionDescription(name = "enable_prompt_caching", type = Type.Bool),
                        OptionDescription(name = "run_on_config", type = Type.Bool),
                        OptionDescription(name = "tools", type = Type.Array)
                    )
                ),
                ServiceConfigDescription(
                    name = "tts",
                    options = listOf(OptionDescription(name = "voice", type = Type.Str))
                )
            ),
            client.describeConfig().await()
        )
    }

    @Test
    fun testLLMContext() = runTestWithConnectedClient {

        val initialMsg = LLMContextMessage(
            role = "system",
            content = "You are a helpful voice assistant."
        )

        Assert.assertEquals(
            LLMContext(listOf(initialMsg)),
            llmHelper.getContext().await()
        )

        val msgToAppend = LLMContextMessage(
            role = "assistant",
            content = "Hi there! How can I help you?"
        )

        llmHelper.appendToMessages(msgToAppend)

        Assert.assertEquals(
            LLMContext(listOf(initialMsg, msgToAppend)),
            llmHelper.getContext().await()
        )

        llmHelper.run(interrupt = true).await()

        llmHelper.setContext(LLMContext(listOf(msgToAppend, msgToAppend)), interrupt = true).await()

        Assert.assertEquals(
            LLMContext(listOf(msgToAppend, msgToAppend)),
            llmHelper.getContext().await()
        )
    }
}
