package ai.rever.boss.components.workspaces

import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.window_panel.SplitViewStateRegistry
import ai.rever.boss.plugin.api.TabRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WindowSpaceSaveCommitTest {
    @TempDir
    lateinit var directory: Path

    private val windowId = "save-contract-test"
    private val states = mutableListOf<SplitViewState>()

    private fun state() =
        SplitViewState(TabRegistry(), windowId).also {
            states.add(it)
            SplitViewStateRegistry.register(windowId, it)
            it.rebindCurrentWorkspace(LAST_SESSION_ID)
        }

    private fun live() =
        LayoutWorkspace(
            id = "extracted",
            name = "Live",
            description = "Live layout",
            layout =
                ai.rever.boss.plugin.workspace.SplitConfig
                    .SinglePanel(PanelConfig("main", emptyList())),
            projectPath = "/current/project",
        )

    @AfterEach
    fun cleanup() {
        SplitViewStateRegistry.unregister(windowId)
        states.forEach { it.dispose() }
    }

    @Test
    fun `failed persistence leaves window identity unchanged and retry succeeds`() =
        runTest {
            val state = state()
            val files = WorkspaceFileManager(directory.toString())
            var fail = true
            val manager =
                WorkspaceManager(files, backgroundScope, writeWorkspace = { item, name ->
                    if (fail) null else files.saveWorkspace(item, name)
                })
            assertTrue(saveWindowSpace(windowId, manager, ::live).isFailure)
            assertEquals(LAST_SESSION_ID, state.currentWorkspaceId)
            fail = false
            val saved = saveWindowSpace(windowId, manager, ::live).getOrThrow()
            assertEquals(saved.id, state.currentWorkspaceId)
            assertEquals(saved, manager.savedCopyOf(saved.id))
        }

    @Test
    fun `rapid saves wait for success and then reuse the committed identity`() =
        runTest {
            val state = state()
            val files = WorkspaceFileManager(directory.toString())
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var writes = 0
            val manager =
                WorkspaceManager(files, backgroundScope, writeWorkspace = { item, name ->
                    if (++writes == 1) {
                        entered.complete(Unit)
                        release.await()
                    }
                    files.saveWorkspace(item, name)
                })
            val first =
                async(start = CoroutineStart.UNDISPATCHED) {
                    saveWindowSpace(windowId, manager, ::live, "Mine")
                }
            entered.await()
            val second =
                async(start = CoroutineStart.UNDISPATCHED) {
                    saveWindowSpace(windowId, manager, ::live, "Mine")
                }
            assertEquals(LAST_SESSION_ID, state.currentWorkspaceId)
            assertFalse(second.isCompleted)
            release.complete(Unit)
            val saved = first.await().getOrThrow()
            assertEquals(saved.id, second.await().getOrThrow().id)
            assertEquals(saved.id, state.currentWorkspaceId)
            assertEquals(1, files.listWorkspaces().size)
        }

    @Test
    fun `switch away and back during persistence cannot rebind a later visit`() =
        runTest {
            val state = state()
            val files = WorkspaceFileManager(directory.toString())
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val manager =
                WorkspaceManager(files, backgroundScope, writeWorkspace = { item, name ->
                    entered.complete(Unit)
                    release.await()
                    files.saveWorkspace(item, name)
                })
            val save = async(start = CoroutineStart.UNDISPATCHED) { saveWindowSpace(windowId, manager, ::live) }
            entered.await()
            state.preserveCurrentState("other")
            state.preserveCurrentState(LAST_SESSION_ID)
            release.complete(Unit)
            val saved = save.await().getOrThrow()
            assertNotEquals(saved.id, state.currentWorkspaceId)
            assertEquals(LAST_SESSION_ID, state.currentWorkspaceId)
            assertEquals(saved, manager.savedCopyOf(saved.id))
        }

    @Test
    fun `closing the window during persistence cannot rebind its state`() =
        runTest {
            val state = state()
            val files = WorkspaceFileManager(directory.toString())
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val manager =
                WorkspaceManager(files, backgroundScope, writeWorkspace = { item, name ->
                    entered.complete(Unit)
                    release.await()
                    files.saveWorkspace(item, name)
                })
            val save = async(start = CoroutineStart.UNDISPATCHED) { saveWindowSpace(windowId, manager, ::live) }
            entered.await()
            SplitViewStateRegistry.unregister(windowId)
            release.complete(Unit)
            assertTrue(save.await().isSuccess)
            assertEquals(LAST_SESSION_ID, state.currentWorkspaceId)
        }
}
