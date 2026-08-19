package com.ctvhouse.sdk.manual;

import androidx.annotation.NonNull;

import com.ctvhouse.sdk.format.Overlay;
import com.ctvhouse.sdk.format.TriggerRoll;

import org.junit.Test;

import java.util.List;

/**
 * Both launchers are configured by chaining, and a Java host has to be able to write that chain
 * without casts. The value of these tests is that they compile.
 */
public class JavaLauncherInteropTest {

    private static final class HostListener implements TriggerRoll.Listener {
        @Override
        public void onOpen() {
        }

        @Override
        public void onImpression(@NonNull List<String> urls) {
        }
    }

    @Test
    public void theSwitchRollChainResolvesFromJava() {
        SwitchRollAd ad = TestLaunchers.switchRollAd()
                .setTagUrl("https://tag.example/vast")
                .setIfa("aa-bb-cc")
                .setIp("1.2.3.4")
                .setListener(new HostListener())
                .setRequestTimeoutMs(5000)
                .setSkipOffsetSeconds(5)
                .setDismissOnCreativeEnd(true)
                .setBannerDurationSeconds(12)
                .setSkipTemplate("Skip")
                .setSkipCountdownTemplate("Skip in {seconds}")
                .setMarkingTemplate("Ad")
                .setSoundEnabled(false)
                .setLogoVisible(false)
                .setBackdropVisible(false)
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

        ad.attach();
        ad.show("card");
        ad.dismiss();
        ad.detach();
    }

    @Test
    public void theStartRollChainResolvesFromJava() {
        StartRollAd ad = TestLaunchers.startRollAd()
                .setTagUrl("https://tag.example/vast")
                .setListener(new HostListener())
                .setSkipOffsetSeconds(5)
                .setBackdropVisible(true)
                .setBannerDurationSeconds(10)
                .setSkipTemplate("Skip");

        ad.attach();
        ad.show("cold");
        ad.dismiss();
        ad.detach();
    }

    @Test
    public void thePauseRollChainResolvesFromJava() {
        PauseRollAd ad = TestLaunchers.pauseRollAd()
                .setTagUrl("https://tag.example/vast")
                .setIfa("aa-bb-cc")
                .setIp("1.2.3.4")
                .setListener(new HostListener())
                .setSkipOffsetSeconds(5)
                .setDismissOnCreativeEnd(false)
                .setBannerDurationSeconds(0)
                .setSkipTemplate("Skip");

        ad.attach();
        ad.trigger("pause");
        ad.close();
        ad.detach();
    }
}
