package ai.rever.boss.plugin

import ai.rever.boss.cli.plugin.ValidatorTestFixturePlugin
import ai.rever.boss.components.plugin.DynamicPluginInfo
import ai.rever.boss.components.plugin.DynamicPluginManager
import ai.rever.boss.plugin.api.CanUnloadResult
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.api.PluginState
import ai.rever.boss.plugin.api.PluginType
import ai.rever.boss.plugin.api.PluginUnloadAware
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.sandbox.PluginSandboxManager
import ai.rever.boss.plugin.sandbox.PluginSandboxManagerImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginRemovalAcrossWindowsTest {
    @Test
    fun `removing a plugin unloads it from every window`() =
        runBlocking {
            val firstSandbox = PluginSandboxManagerImpl()
            val secondSandbox = PluginSandboxManagerImpl()
            val first = createManager(firstSandbox)
            val second = createManager(secondSandbox)
            val pluginId = "com.example.remove-across-windows"

            try {
                addPluginState(first, pluginId)
                addPluginState(second, pluginId)

                assertTrue(first.isInstalled(pluginId))
                assertTrue(second.isInstalled(pluginId))

                val result = PluginRemoval.remove(pluginId, "", first)

                assertTrue(result.isSuccess, "Removal failed: ${result.exceptionOrNull()}")
                assertFalse(first.isInstalled(pluginId))
                assertFalse(
                    second.isInstalled(pluginId),
                    "Removing a plugin in one window must unload it from the other window",
                )
            } finally {
                first.disposeWindow()
                second.disposeWindow()
                firstSandbox.dispose()
                secondSandbox.dispose()
            }
        }

    @Test
    fun `protected plugin in another window blocks removal before any unload`() =
        runBlocking {
            val firstSandbox = PluginSandboxManagerImpl()
            val secondSandbox = PluginSandboxManagerImpl()
            val first = createManager(firstSandbox)
            val second = createManager(secondSandbox)
            val pluginId = "com.example.protected-across-windows"
            val jar = Files.createTempFile("protected-plugin-", ".jar").toFile()

            try {
                addPluginState(first, pluginId)
                addPluginState(second, pluginId, canUnload = false)

                val result = PluginRemoval.remove(pluginId, jar.absolutePath, first)

                assertTrue(result.isFailure)
                assertTrue(first.isInstalled(pluginId), "The first window must remain unchanged")
                assertTrue(second.isInstalled(pluginId), "The protected window must remain unchanged")
                assertTrue(jar.exists(), "A refused removal must not delete the JAR")
            } finally {
                first.disposeWindow()
                second.disposeWindow()
                firstSandbox.dispose()
                secondSandbox.dispose()
                jar.delete()
            }
        }

    @Test
    fun `failed unload in another window preserves the plugin JAR`() =
        runBlocking {
            val pluginId = "com.example.failed-removal-across-windows"
            val firstSandbox = PluginSandboxManagerImpl()
            val secondSandbox = PluginSandboxManagerImpl()
            val failingSandbox =
                object : PluginSandboxManager by secondSandbox {
                    override suspend fun removeSandbox(pluginId: String) {
                        if (pluginId == "com.example.failed-removal-across-windows") {
                            error("Second window teardown failed")
                        }
                        secondSandbox.removeSandbox(pluginId)
                    }
                }
            val first = createManager(firstSandbox)
            val second = createManager(failingSandbox)
            val jar = Files.createTempFile("failed-plugin-removal-", ".jar").toFile()

            try {
                addPluginState(first, pluginId)
                addPluginState(second, pluginId)

                val result = PluginRemoval.remove(pluginId, jar.absolutePath, first)

                assertTrue(result.isFailure, "Removal must report the second window's failure")
                assertTrue(second.isInstalled(pluginId), "The failed window still owns the plugin")
                assertTrue(jar.exists(), "The JAR must remain while any window owns the plugin")
            } finally {
                first.disposeWindow()
                second.disposeWindow()
                firstSandbox.dispose()
                secondSandbox.dispose()
                jar.delete()
            }
        }

    @Test
    fun `removal unloads two real plugin instances before deleting the JAR`() =
        runBlocking {
            val pluginId = "com.example.real-removal-across-windows"
            val jar = createRealPluginJar(pluginId)
            val firstSandbox = PluginSandboxManagerImpl()
            val secondSandbox = PluginSandboxManagerImpl()
            val first = loadedManager(firstSandbox)
            val second = loadedManager(secondSandbox)

            try {
                assertTrue(first.installPlugin(jar.absolutePath).isSuccess)
                assertTrue(second.installPlugin(jar.absolutePath).isSuccess)
                assertTrue(first.isInstalled(pluginId))
                assertTrue(second.isInstalled(pluginId))

                val result = PluginRemoval.remove(pluginId, jar.absolutePath, first)

                assertTrue(result.isSuccess, "Removal failed: ${result.exceptionOrNull()}")
                assertFalse(first.isInstalled(pluginId))
                assertFalse(second.isInstalled(pluginId))
                assertFalse(jar.exists(), "The JAR should be deleted after both unloads")
            } finally {
                first.disposeWindow()
                second.disposeWindow()
                firstSandbox.dispose()
                secondSandbox.dispose()
                jar.delete()
            }
        }

    @Test
    fun `another window's unload veto leaves both windows unchanged`() =
        runBlocking {
            val pluginId = "com.example.veto-across-windows"
            val jar = createRealPluginJar(pluginId)
            val firstSandbox = PluginSandboxManagerImpl()
            val secondSandbox = PluginSandboxManagerImpl()
            val first = loadedManager(firstSandbox)
            val second = loadedManager(secondSandbox)
            val veto =
                object : PluginUnloadAware {
                    override fun checkCanUnload(pluginId: String): CanUnloadResult =
                        CanUnloadResult.NotAllowed(listOf("Second window still needs the plugin"))

                    override fun prepareForUnload(pluginId: String) = Unit
                }

            try {
                assertTrue(first.installPlugin(jar.absolutePath).isSuccess)
                assertTrue(second.installPlugin(jar.absolutePath).isSuccess)
                second.registerUnloadAware(veto)

                val result = PluginRemoval.remove(pluginId, jar.absolutePath, first)

                assertTrue(result.isFailure, "The second window's veto must stop removal")
                assertTrue(first.isInstalled(pluginId), "Check all windows before unloading the first")
                assertTrue(second.isInstalled(pluginId))
                assertTrue(jar.exists())
            } finally {
                first.disposeWindow()
                second.disposeWindow()
                firstSandbox.dispose()
                secondSandbox.dispose()
                jar.delete()
            }
        }

    @Test
    fun `a loaded dependent does not bypass another window's unload veto`() =
        runBlocking {
            val pluginId = "com.example.target-with-dependent"
            val dependentId = "com.example.depends-on-target"
            val jar = createRealPluginJar(pluginId)
            val dependentJar = createRealPluginJar(dependentId, dependsOn = pluginId)
            val firstSandbox = PluginSandboxManagerImpl()
            val secondSandbox = PluginSandboxManagerImpl()
            val first = loadedManager(firstSandbox)
            val second = loadedManager(secondSandbox)
            val veto =
                object : PluginUnloadAware {
                    override fun checkCanUnload(pluginId: String): CanUnloadResult =
                        CanUnloadResult.NotAllowed(listOf("Second window cannot release this plugin"))

                    override fun prepareForUnload(pluginId: String) = Unit
                }

            try {
                assertTrue(first.installPlugin(jar.absolutePath).isSuccess)
                assertTrue(second.installPlugin(jar.absolutePath).isSuccess)
                assertTrue(second.installPlugin(dependentJar.absolutePath).isSuccess)
                assertTrue(
                    second.dependentsOf(pluginId).any { it.pluginId == dependentId },
                    "The second window must have a loaded dependent for this test",
                )
                second.registerUnloadAware(veto)

                val result = withTimeout(5_000) { PluginRemoval.remove(pluginId, jar.absolutePath, first) }

                assertTrue(result.isFailure)
                assertTrue(first.isInstalled(pluginId))
                assertTrue(second.isInstalled(pluginId))
                assertTrue(jar.exists())
            } finally {
                first.disposeWindow()
                second.disposeWindow()
                firstSandbox.dispose()
                secondSandbox.dispose()
                jar.delete()
                dependentJar.delete()
            }
        }

    private fun createRealPluginJar(
        pluginId: String,
        dependsOn: String? = null,
    ): java.io.File {
        val jar = Files.createTempFile("real-plugin-removal-", ".jar").toFile()
        val pluginClass = ValidatorTestFixturePlugin::class.java
        val classEntry = pluginClass.name.replace('.', '/') + ".class"
        val dependencies =
            if (dependsOn == null) {
                "[]"
            } else {
                """[{"pluginId":"$dependsOn","version":"1.0.0","optional":false}]"""
            }

        JarOutputStream(jar.outputStream()).use { output ->
            output.putNextEntry(JarEntry("META-INF/boss-plugin/plugin.json"))
            output.write(
                """
                {
                "manifestVersion": 1,
                "pluginId": "$pluginId",
                "displayName": "Real removal test",
                "version": "1.0.0",
                "apiVersion": "1.0.0",
                "mainClass": "${pluginClass.name}",
                "dependencies": $dependencies
                }
                """.trimIndent().toByteArray(),
            )
            output.closeEntry()

            output.putNextEntry(JarEntry(classEntry))
            output.write(pluginClass.classLoader.getResourceAsStream(classEntry)!!.use { it.readBytes() })
            output.closeEntry()
        }
        return jar
    }

    private fun loadedManager(sandbox: PluginSandboxManagerImpl): DynamicPluginManager {
        val context =
            object : PluginContext {
                override val panelRegistry = PanelRegistry()
                override val tabRegistry = TabRegistry()
                override val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            }
        return DynamicPluginManager(
            context.panelRegistry,
            context.tabRegistry,
            sandbox,
            createSandboxedContext = { _, _ -> context },
        )
    }

    private fun createManager(sandbox: PluginSandboxManager) =
        DynamicPluginManager(
            PanelRegistry(),
            TabRegistry(),
            sandbox,
            createSandboxedContext = { _, _ -> error("No plugin is loaded in this fixture") },
        )

    private fun addPluginState(
        manager: DynamicPluginManager,
        pluginId: String,
        canUnload: Boolean = true,
    ) {
        val info =
            DynamicPluginInfo(
                manifest =
                    PluginManifest(
                        pluginId = pluginId,
                        displayName = "Cross-window removal test",
                        version = "1.0.0",
                        apiVersion = "1.0",
                        mainClass = "example.Plugin",
                        type = PluginType.PANEL,
                        canUnload = canUnload,
                    ),
                jarPath = "",
                state = PluginState.ERROR,
                loadedAt = 0L,
                enabled = false,
            )

        manager.javaClass
            .getDeclaredMethod("updatePluginState", String::class.java, DynamicPluginInfo::class.java)
            .apply { isAccessible = true }
            .invoke(manager, pluginId, info)
    }
}
