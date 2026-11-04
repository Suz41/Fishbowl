package com.fishbowl.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit Test Suite for FishbowlQuickSettingsTileService.
 * Validates:
 *  1. State mapping matrix (RUNNING, STOPPED, STARTING, STOPPING, INITIALIZING, FAILED, CRASHED, CRASH_LOOP, UNINITIALIZED, null)
 *  2. User tap interaction logic (Start, Stop, None/Blocked)
 *  3. Debounce & duplicate-action prevention under rapid clicks
 *  4. Server state transition listener integration & process crash handling
 */
public class FishbowlQuickSettingsTileTest {

    // -- 1. State Mapping Tests ------------------------------------------------

    @Test
    public void testComputePresentationRunning() {
        FishbowlQuickSettingsTileService.TilePresentation p =
                FishbowlQuickSettingsTileService.computePresentation(JellyfinController.State.RUNNING);
        assertNotNull(p);
        assertEquals(FishbowlQuickSettingsTileService.STATE_ACTIVE, p.state);
        assertEquals("Jellyfin", p.label);
        assertEquals("Running", p.subtitle);
        assertTrue(p.contentDescription.contains("running"));
    }

    @Test
    public void testComputePresentationStopped() {
        FishbowlQuickSettingsTileService.TilePresentation p =
                FishbowlQuickSettingsTileService.computePresentation(JellyfinController.State.STOPPED);
        assertNotNull(p);
        assertEquals(FishbowlQuickSettingsTileService.STATE_INACTIVE, p.state);
        assertEquals("Jellyfin", p.label);
        assertEquals("Stopped", p.subtitle);
        assertTrue(p.contentDescription.contains("stopped"));
    }

    @Test
    public void testComputePresentationStartingAndInitializing() {
        // STARTING -> Transitional (Unavailable)
        FishbowlQuickSettingsTileService.TilePresentation pStarting =
                FishbowlQuickSettingsTileService.computePresentation(JellyfinController.State.STARTING);
        assertNotNull(pStarting);
        assertEquals(FishbowlQuickSettingsTileService.STATE_UNAVAILABLE, pStarting.state);
        assertEquals("Starting...", pStarting.subtitle);

        // INITIALIZING -> Transitional (Unavailable)
        FishbowlQuickSettingsTileService.TilePresentation pInit =
                FishbowlQuickSettingsTileService.computePresentation(JellyfinController.State.INITIALIZING);
        assertNotNull(pInit);
        assertEquals(FishbowlQuickSettingsTileService.STATE_UNAVAILABLE, pInit.state);
        assertEquals("Starting...", pInit.subtitle);
    }

    @Test
    public void testComputePresentationStopping() {
        FishbowlQuickSettingsTileService.TilePresentation p =
                FishbowlQuickSettingsTileService.computePresentation(JellyfinController.State.STOPPING);
        assertNotNull(p);
        assertEquals(FishbowlQuickSettingsTileService.STATE_UNAVAILABLE, p.state);
        assertEquals("Stopping...", p.subtitle);
        assertTrue(p.contentDescription.contains("stopping"));
    }

    @Test
    public void testComputePresentationFailedCrashedAndCrashLoop() {
        // FAILED -> Inactive with Error
        FishbowlQuickSettingsTileService.TilePresentation pFailed =
                FishbowlQuickSettingsTileService.computePresentation(JellyfinController.State.FAILED);
        assertNotNull(pFailed);
        assertEquals(FishbowlQuickSettingsTileService.STATE_INACTIVE, pFailed.state);
        assertEquals("Error", pFailed.subtitle);

        // CRASHED -> Inactive with Error
        FishbowlQuickSettingsTileService.TilePresentation pCrashed =
                FishbowlQuickSettingsTileService.computePresentation(JellyfinController.State.CRASHED);
        assertNotNull(pCrashed);
        assertEquals(FishbowlQuickSettingsTileService.STATE_INACTIVE, pCrashed.state);
        assertEquals("Error", pCrashed.subtitle);

        // CRASH_LOOP -> Inactive with Error
        FishbowlQuickSettingsTileService.TilePresentation pLoop =
                FishbowlQuickSettingsTileService.computePresentation(JellyfinController.State.CRASH_LOOP);
        assertNotNull(pLoop);
        assertEquals(FishbowlQuickSettingsTileService.STATE_INACTIVE, pLoop.state);
        assertEquals("Error", pLoop.subtitle);
    }

    @Test
    public void testComputePresentationUninitializedAndNull() {
        FishbowlQuickSettingsTileService.TilePresentation pUninit =
                FishbowlQuickSettingsTileService.computePresentation(JellyfinController.State.UNINITIALIZED);
        assertNotNull(pUninit);
        assertEquals(FishbowlQuickSettingsTileService.STATE_INACTIVE, pUninit.state);
        assertEquals("Stopped", pUninit.subtitle);

        FishbowlQuickSettingsTileService.TilePresentation pNull =
                FishbowlQuickSettingsTileService.computePresentation(null);
        assertNotNull(pNull);
        assertEquals(FishbowlQuickSettingsTileService.STATE_INACTIVE, pNull.state);
        assertEquals("Stopped", pNull.subtitle);
    }

    // -- 2. User Interaction & Action Resolution Tests -------------------------

    @Test
    public void testResolveActionWhenStoppedStartsServer() {
        FishbowlQuickSettingsTileService.DesiredAction action =
                FishbowlQuickSettingsTileService.resolveNextAction(JellyfinController.State.STOPPED);
        assertEquals(FishbowlQuickSettingsTileService.DesiredAction.START, action);
    }

    @Test
    public void testResolveActionWhenRunningStopsServer() {
        FishbowlQuickSettingsTileService.DesiredAction action =
                FishbowlQuickSettingsTileService.resolveNextAction(JellyfinController.State.RUNNING);
        assertEquals(FishbowlQuickSettingsTileService.DesiredAction.STOP, action);
    }

    @Test
    public void testResolveActionWhenStartingDoesNotTriggerDuplicate() {
        FishbowlQuickSettingsTileService.DesiredAction action =
                FishbowlQuickSettingsTileService.resolveNextAction(JellyfinController.State.STARTING);
        assertEquals(FishbowlQuickSettingsTileService.DesiredAction.NONE, action);
    }

    @Test
    public void testResolveActionWhenStoppingDoesNotTriggerDuplicate() {
        FishbowlQuickSettingsTileService.DesiredAction action =
                FishbowlQuickSettingsTileService.resolveNextAction(JellyfinController.State.STOPPING);
        assertEquals(FishbowlQuickSettingsTileService.DesiredAction.NONE, action);
    }

    @Test
    public void testResolveActionWhenFailedOrCrashedRequestsRestart() {
        assertEquals(FishbowlQuickSettingsTileService.DesiredAction.START,
                FishbowlQuickSettingsTileService.resolveNextAction(JellyfinController.State.FAILED));
        assertEquals(FishbowlQuickSettingsTileService.DesiredAction.START,
                FishbowlQuickSettingsTileService.resolveNextAction(JellyfinController.State.CRASHED));
        assertEquals(FishbowlQuickSettingsTileService.DesiredAction.START,
                FishbowlQuickSettingsTileService.resolveNextAction(JellyfinController.State.CRASH_LOOP));
    }

    // -- 3. Debounce & Concurrency Guard Tests ---------------------------------

    @Test
    public void testClickDebouncePreventsRapidDuplicateTaps() {
        long t0 = 100000L;
        // First click at t0: allowed
        assertTrue(FishbowlQuickSettingsTileService.isClickAllowed(t0, 0L));

        // Rapid second click 150ms later: blocked
        assertFalse(FishbowlQuickSettingsTileService.isClickAllowed(t0 + 150L, t0));

        // Rapid third click 999ms later: blocked (< 1000ms threshold)
        assertFalse(FishbowlQuickSettingsTileService.isClickAllowed(t0 + 999L, t0));

        // Fourth click 1001ms later: allowed (>= 1000ms threshold)
        assertTrue(FishbowlQuickSettingsTileService.isClickAllowed(t0 + 1001L, t0));
    }

    // -- 4. State Synchronization & Crash Recovery -----------------------------

    @Test
    public void testServerListenerTracksLifecycleAndCrashRecovery() {
        AtomicReference<FishbowlQuickSettingsTileService.TilePresentation> currentTile = new AtomicReference<>();
        AtomicBoolean wasNotified = new AtomicBoolean(false);

        JellyfinController.Listener listener = state -> {
            wasNotified.set(true);
            currentTile.set(FishbowlQuickSettingsTileService.computePresentation(state));
        };

        // 1. Initial State: Stopped
        listener.onServerChanged(JellyfinController.State.STOPPED);
        assertEquals(FishbowlQuickSettingsTileService.STATE_INACTIVE, currentTile.get().state);
        assertEquals("Stopped", currentTile.get().subtitle);

        // 2. User tapped start -> State transitions to STARTING
        listener.onServerChanged(JellyfinController.State.STARTING);
        assertEquals(FishbowlQuickSettingsTileService.STATE_UNAVAILABLE, currentTile.get().state);
        assertEquals("Starting...", currentTile.get().subtitle);

        // 3. Server becomes healthy -> State transitions to RUNNING
        listener.onServerChanged(JellyfinController.State.RUNNING);
        assertEquals(FishbowlQuickSettingsTileService.STATE_ACTIVE, currentTile.get().state);
        assertEquals("Running", currentTile.get().subtitle);

        // 4. Server experiences unexpected process termination -> State transitions to CRASHED
        listener.onServerChanged(JellyfinController.State.CRASHED);
        assertEquals(FishbowlQuickSettingsTileService.STATE_INACTIVE, currentTile.get().state);
        assertEquals("Error", currentTile.get().subtitle);

        // 5. User taps tile to restart -> transitions back to STARTING -> RUNNING
        listener.onServerChanged(JellyfinController.State.STARTING);
        assertEquals(FishbowlQuickSettingsTileService.STATE_UNAVAILABLE, currentTile.get().state);
        listener.onServerChanged(JellyfinController.State.RUNNING);
        assertEquals(FishbowlQuickSettingsTileService.STATE_ACTIVE, currentTile.get().state);

        // 6. User gracefully stops -> STOPPING -> STOPPED
        listener.onServerChanged(JellyfinController.State.STOPPING);
        assertEquals(FishbowlQuickSettingsTileService.STATE_UNAVAILABLE, currentTile.get().state);
        listener.onServerChanged(JellyfinController.State.STOPPED);
        assertEquals(FishbowlQuickSettingsTileService.STATE_INACTIVE, currentTile.get().state);
    }
}
