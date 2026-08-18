package dev.kwery.compose

import androidx.compose.runtime.Applier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * An [Applier] over no node tree at all.
 *
 * Compose's runtime and its node tree are separable: `remember`,
 * `LaunchedEffect`, `DisposableEffect` and recomposition all work without
 * anything ever being emitted. The Kwery bindings emit no nodes, so there is
 * nothing to apply.
 */
private class UnitApplier : Applier<Unit> {
    override val current: Unit get() = Unit
    override fun down(node: Unit) = Unit
    override fun up() = Unit
    override fun insertTopDown(index: Int, instance: Unit) = Unit
    override fun insertBottomUp(index: Int, instance: Unit) = Unit
    override fun remove(index: Int, count: Int) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
    override fun clear() = Unit
}

/**
 * A composition driven entirely by hand, on the test dispatcher.
 *
 * Deliberately not Robolectric and not an emulator. `rememberQuery` is an
 * adapter over a `Flow`, so what has to be proven is composition behaviour —
 * when a subscription is created, kept, or torn down — and none of that
 * involves a pixel. A headless composition also keeps the suite on the virtual
 * clock, which the [dev.kwery.test.TestQueryClient] convention requires.
 *
 * Rendering paths (loading / error / refreshing) do need a real UI, and are
 * tracked as device-only work in the roadmap rather than faked here.
 */
internal class TestComposition(
    scope: CoroutineScope,
    private val settle: () -> Unit,
) {
    private val clock = BroadcastFrameClock()
    private val recomposer = Recomposer(scope.coroutineContext + clock)
    private val composition = Composition(UnitApplier(), recomposer)

    init {
        scope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        settle()
    }

    fun setContent(content: @Composable () -> Unit) {
        composition.setContent(content)
        frame()
    }

    /**
     * Advance one frame: publish pending snapshot writes, let the recomposer
     * notice them, then release the frame it is waiting on.
     *
     * Repeated because a recomposition can schedule effects that themselves
     * request another frame — `collectAsState` collecting its first value being
     * the ordinary case.
     */
    fun frame(times: Int = 3) {
        repeat(times) {
            Snapshot.sendApplyNotifications()
            settle()
            clock.sendFrame(0L)
            settle()
        }
    }

    fun dispose() {
        composition.dispose()
        recomposer.cancel()
        settle()
    }
}
