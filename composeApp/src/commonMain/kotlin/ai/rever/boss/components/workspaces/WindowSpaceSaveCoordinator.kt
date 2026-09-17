package ai.rever.boss.components.workspaces

import ai.rever.boss.components.window_panel.SplitViewStateRegistry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes saves from both UI entry points without treating persistence as a Space switch. */
internal class WindowSpaceSaveCoordinator {
    private val saves = Mutex()

    suspend fun save(
        isCurrentVisit: () -> Boolean,
        snapshot: () -> LayoutWorkspace,
        persist: suspend (LayoutWorkspace) -> Result<LayoutWorkspace>,
        rebind: (String) -> Unit,
    ): Result<LayoutWorkspace> =
        saves.withLock {
            if (!isCurrentVisit()) {
                return@withLock Result.failure(IllegalStateException("The window changed before saving"))
            }
            // Read after earlier saves finish so another click uses their committed identity.
            persist(snapshot()).onSuccess { saved ->
                if (isCurrentVisit()) rebind(saved.id)
            }
        }
}

internal suspend fun saveWindowSpace(
    windowId: String?,
    manager: WorkspaceManager,
    liveLayout: () -> LayoutWorkspace,
    name: String? = null,
): Result<LayoutWorkspace> {
    val state =
        windowId?.let(SplitViewStateRegistry::getState)
            ?: return Result.failure(IllegalStateException("The window is no longer open"))
    val visit = state.workspaceVisit
    manager.awaitLoaded()
    return state.spaceSaveCoordinator.save(
        isCurrentVisit = {
            windowId?.let(SplitViewStateRegistry::getState) === state && state.workspaceVisit == visit
        },
        snapshot = {
            spaceSnapshotForSave(
                state.currentWorkspaceId,
                liveLayout(),
                manager.workspaces.value,
                manager.currentWorkspace.value,
            )
        },
        persist = { manager.saveWorkspaceAwait(it, name) },
        rebind = state::rebindCurrentWorkspace,
    )
}
