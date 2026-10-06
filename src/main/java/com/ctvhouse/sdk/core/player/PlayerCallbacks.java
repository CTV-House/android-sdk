package com.ctvhouse.sdk.core.player;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.DeviceInfo;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Metadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.UnstableApi;
import java.util.List;

/**
 * Concrete {@link Player.Listener}. Some TV ART builds never dispatch Java default methods, so a
 * Kotlin {@code Player.Listener} dies on the first ExoPlayer notify ({@code AbstractMethodError}).
 */
@UnstableApi
@SuppressWarnings("deprecation")
public class PlayerCallbacks implements Player.Listener {

    @Override
    public void onEvents(Player player, Player.Events events) {}

    @Override
    public void onTimelineChanged(Timeline timeline, int reason) {}

    @Override
    public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {}

    @Override
    public void onTracksChanged(Tracks tracks) {}

    @Override
    public void onMediaMetadataChanged(MediaMetadata mediaMetadata) {}

    @Override
    public void onPlaylistMetadataChanged(MediaMetadata mediaMetadata) {}

    @Override
    public void onIsLoadingChanged(boolean isLoading) {}

    @Override
    public void onLoadingChanged(boolean isLoading) {}

    @Override
    public void onAvailableCommandsChanged(Player.Commands availableCommands) {}

    @Override
    public void onTrackSelectionParametersChanged(TrackSelectionParameters parameters) {}

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {}

    @Override
    public void onPlaybackStateChanged(int playbackState) {}

    @Override
    public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {}

    @Override
    public void onPlaybackSuppressionReasonChanged(int playbackSuppressionReason) {}

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {}

    @Override
    public void onRepeatModeChanged(int repeatMode) {}

    @Override
    public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {}

    @Override
    public void onPlayerError(PlaybackException error) {}

    @Override
    public void onPlayerErrorChanged(@Nullable PlaybackException error) {}

    @Override
    public void onPositionDiscontinuity(int reason) {}

    @Override
    public void onPositionDiscontinuity(
            Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {}

    @Override
    public void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {}

    @Override
    public void onSeekBackIncrementChanged(long seekBackIncrementMs) {}

    @Override
    public void onSeekForwardIncrementChanged(long seekForwardIncrementMs) {}

    @Override
    public void onMaxSeekToPreviousPositionChanged(long maxSeekToPreviousPositionMs) {}

    @Override
    public void onAudioSessionIdChanged(int audioSessionId) {}

    @Override
    public void onAudioAttributesChanged(AudioAttributes audioAttributes) {}

    @Override
    public void onVolumeChanged(float volume) {}

    @Override
    public void onSkipSilenceEnabledChanged(boolean skipSilenceEnabled) {}

    @Override
    public void onDeviceInfoChanged(DeviceInfo deviceInfo) {}

    @Override
    public void onDeviceVolumeChanged(int volume, boolean muted) {}

    @Override
    public void onVideoSizeChanged(VideoSize videoSize) {}

    @Override
    public void onSurfaceSizeChanged(int width, int height) {}

    @Override
    public void onRenderedFirstFrame() {}

    @Override
    public void onCues(List<Cue> cues) {}

    @Override
    public void onCues(CueGroup cueGroup) {}

    @Override
    public void onMetadata(Metadata metadata) {}
}
