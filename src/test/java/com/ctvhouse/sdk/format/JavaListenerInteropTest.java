package com.ctvhouse.sdk.format;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A Java host must be able to subscribe to the events it cares about and ignore the rest of
 * the VAST catalog, and the callbacks it does override have to arrive from a running slot.
 */
public class JavaListenerInteropTest {

    private static final class HostListener implements TriggerRoll.Listener {
        final List<String> calls = new ArrayList<>();

        @Override
        public void onOpen() {
            calls.add("open");
        }

        @Override
        public void onImpression(@NonNull List<String> urls) {
            calls.add("impression:" + urls.size());
        }
    }

    @Test
    public void aJavaListenerIsCalledByTheRunningSlot() {
        HostListener listener = new HostListener();
        TriggerRoll slot = TestSlots.fillingTriggerRoll()
                .setListener(listener)
                .setSkipOffsetSeconds(5)
                .setSkipTemplate("Skip");

        slot.attach();
        slot.trigger("pause");

        assertEquals("impression:1", listener.calls.get(0));
        assertEquals("open", listener.calls.get(1));
        slot.detach();
    }

    /**
     * The whole builder chain has to resolve from Java without a cast. A backing field named after
     * a setter used to publish a second candidate and made the call ambiguous, so keep every
     * setter in one chain here: the value of this test is that it compiles.
     */
    @Test
    public void theBuilderChainResolvesFromJava() {
        TriggerRoll slot = TestSlots.fillingTriggerRoll()
                .setListener(new HostListener())
                .setRequestTimeoutMs(5000)
                .setMarkingTemplate("Ad")
                .setSkipTemplate("Skip")
                .setSkipCountdownTemplate("Skip in {seconds}")
                .setSkipOffsetSeconds(5)
                .setSoundEnabled(false)
                .setLogoVisible(false)
                .setInfoVisible(false)
                .setPauseVisible(false)
                .setMuteVisible(false)
                .setDebugLogging(false)
                .setControlsPosition(
                        Overlay.ControlsHorizontal.LEFT, Overlay.ControlsVertical.BOTTOM)
                .setMarkingPosition(
                        Overlay.ControlsHorizontal.RIGHT, Overlay.ControlsVertical.TOP)
                .setSkipPosition(
                        Overlay.ControlsHorizontal.RIGHT, Overlay.ControlsVertical.BOTTOM);

        slot.attach();
        slot.trigger("pause");
        slot.close();
        slot.detach();
    }

    @Test
    public void inheritedCallbacksAreNoOps() {
        HostListener listener = new HostListener();

        listener.onNoAd();
        listener.onError("boom");
        listener.onClose();
        listener.onComplete(Collections.<String>emptyList());
        listener.onTracking("custom", Collections.<String>emptyList());

        assertTrue(listener.calls.isEmpty());
    }
}
