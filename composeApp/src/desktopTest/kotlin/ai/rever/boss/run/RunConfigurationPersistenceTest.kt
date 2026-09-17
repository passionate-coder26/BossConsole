package ai.rever.boss.run

import ai.rever.boss.plugin.pathutils.BossDirectories
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunConfigurationPersistenceTest {
    @Test
    fun `parallel additions preserve every configuration in memory and on disk`() =
        runBlocking {
            val prefix = UUID.randomUUID().toString()
            val configs =
                (0 until 24).map { index ->
                    RunConfiguration(
                        id = "$prefix-$index",
                        name = "Concurrent run $index",
                        type = RunConfigurationType.MAIN_FUNCTION,
                        filePath = "/example/$prefix/$index.kt",
                        lineNumber = 1,
                        language = Language.KOTLIN,
                        command = "",
                        workingDirectory = "",
                    )
                }
            val start = CompletableDeferred<Unit>()

            try {
                val jobs =
                    configs.map { config ->
                        async(Dispatchers.Default) {
                            start.await()
                            RunConfigurationManager.addConfiguration(config)
                        }
                    }
                start.complete(Unit)
                jobs.awaitAll()

                val expected = configs.map { it.id }.toSet()
                val inMemory =
                    RunConfigurationManager.currentSettings.value.configurations
                        .map { it.id }
                        .toSet()
                val saved =
                    Json
                        .decodeFromString<RunConfigurationSettings>(
                            BossDirectories.resolve("run-configurations.json").readText(),
                        ).configurations
                        .map { it.id }
                        .toSet()

                assertTrue(inMemory.containsAll(expected), "Concurrent updates must not drop a configuration")
                assertEquals(inMemory, saved, "The last completed update must be the one on disk")
            } finally {
                configs.forEach { RunConfigurationManager.removeConfiguration(it.id) }
            }
        }

    @Test
    fun `failed startup cleanup write preserves loaded configurations`() {
        val file = Files.createTempFile("run-config-startup-", ".json").toFile()
        val original =
            RunConfiguration(
                id = "keep-this-configuration",
                name = "Main",
                type = RunConfigurationType.MAIN_FUNCTION,
                filePath = "/example/Main.kt",
                lineNumber = 1,
                language = Language.KOTLIN,
                command = "",
                workingDirectory = "",
            )
        val duplicate = original.copy(id = "duplicate-path")

        try {
            file.writeText(
                Json.encodeToString(
                    RunConfigurationSettings.serializer(),
                    RunConfigurationSettings(configurations = listOf(original, duplicate)),
                ),
            )

            val loaded =
                RunConfigurationManager.loadSettingsFromFile(file) { _, _ ->
                    error("Simulated cleanup write failure")
                }

            assertEquals(
                listOf(original.id),
                loaded.configurations.map { it.id },
                "A failed cleanup write must not discard configurations loaded from disk",
            )
            assertEquals(
                2,
                Json.decodeFromString<RunConfigurationSettings>(file.readText()).configurations.size,
                "The failed cleanup must leave the original file intact",
            )
        } finally {
            file.delete()
        }
    }
}
