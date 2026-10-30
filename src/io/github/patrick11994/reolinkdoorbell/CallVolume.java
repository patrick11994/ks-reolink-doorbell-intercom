// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

import android.content.Context;
import android.media.AudioManager;

/**
 * The voice-call volume the door audio plays at. KS's own sliders do not cover it and the panel has no volume
 * buttons, so the viewer sets it for the length of a call and puts the previous level back afterwards.
 */
final class CallVolume {
    private final AudioManager audio;
    private int previous = -1;

    CallVolume(Context context) {
        audio = (AudioManager) context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
    }

    /**
     * Sets the call volume, 0 to 100 percent, remembering the level from before the first change. Returns null when
     * Android took it, or what it left the volume at when it did not (a fixed-volume device, such as an Echo Show
     * image), so the plugin can turn its own playback down instead.
     */
    synchronized String apply(int percent) {
        try {
            if (previous < 0) previous = audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
            int min = android.os.Build.VERSION.SDK_INT >= 28 ? audio.getStreamMinVolume(AudioManager.STREAM_VOICE_CALL) : 0;
            int index = Math.max(min, Math.min(max, Math.round(max * Math.max(0, Math.min(100, percent)) / 100f)));
            if (audio.isVolumeFixed()) return "fixed";
            audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, index, 0);
            int now = audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
            return now == index ? null : now + " of " + max + " instead of " + index;
        } catch (RuntimeException refused) {
            return "refused (" + refused.getMessage() + ")";  // Do Not Disturb, for one
        }
    }

    /** Puts back the level from before the call. */
    synchronized void restore() {
        if (previous < 0) return;
        try {
            audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, previous, 0);
        } catch (RuntimeException ignored) {
            // Nothing more to do.
        }
        previous = -1;
    }
}
