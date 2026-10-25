// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import me.jxl.kiosk.plugins.KsTheme;
import io.github.patrick11994.reolinkdoorbell.RingController.State;

/**
 * The full-screen doorbell viewer: the live picture (a TextureView, which KS overlays support), the doorbell's name,
 * a volume slider while door audio plays (the panel has no volume buttons), and the call buttons.
 * Built and updated on the main thread only.
 */
final class DoorbellView extends FrameLayout {
    interface Callback {
        void hangUp();
        void speaker();
        void answer();
        void toggleSpeaker();
        void toggleMic();
        void touched();
        /** The picture's surface exists, or is gone; the plugin attaches and detaches the decoder. */
        void surfaceReady(Surface surface);
        void surfaceGone();
        /** The call volume slider moved, 0 to 100; done when the finger lifts. */
        void volumeChanged(int percent, boolean done);
    }

    private final Callback callback;
    private final String name;
    private final Texts texts;
    private KsTheme theme;
    private final FrameLayout video;
    private final TextureView picture;
    private final TextView placeholder;
    private final LinearLayout volumeRow;
    private final ImageView volumeIcon;
    private final SeekBar volume;
    private int videoWidth, videoHeight;
    private boolean pictureShown;
    private long countdownStart, countdownMillis;  // the auto-close countdown the ring around Hang up shows
    private final TextView title;
    private final TextView speaking;
    private final LinearLayout buttons;
    private State state;
    private boolean speakerOn;
    private boolean micOn;

    DoorbellView(Context context, KsTheme theme, Texts texts, String name, State state, int volumePercent, Callback callback) {
        super(context);
        this.texts = texts;
        this.theme = theme;
        this.name = name;
        this.callback = callback;
        this.state = state;
        setBackgroundColor(Color.BLACK);

        video = new FrameLayout(context);
        picture = new TextureView(context);
        picture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                fit();
                safely(() -> callback.surfaceReady(new Surface(texture)));
            }

            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
                fit();
            }

            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                safely(callback::surfaceGone);
                return true;
            }

            @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {
                if (!pictureShown) {
                    pictureShown = true;
                    placeholder.setVisibility(GONE);
                }
            }
        });
        video.addView(picture, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        placeholder = new TextView(context);
        placeholder.setGravity(Gravity.CENTER);
        video.addView(placeholder, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(video, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LinearLayout top = new LinearLayout(context);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setGravity(Gravity.CENTER_HORIZONTAL);
        title = new TextView(context);
        title.setGravity(Gravity.CENTER);
        top.addView(title);
        speaking = new TextView(context);
        speaking.setGravity(Gravity.CENTER);
        speaking.setVisibility(GONE);
        top.addView(speaking);
        LayoutParams topParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP);
        addView(top, topParams);

        LinearLayout bottom = new LinearLayout(context);
        bottom.setOrientation(LinearLayout.VERTICAL);
        volumeRow = new LinearLayout(context);
        volumeRow.setOrientation(LinearLayout.HORIZONTAL);
        volumeRow.setGravity(Gravity.CENTER_VERTICAL);
        volumeRow.setVisibility(GONE);
        volumeIcon = new ImageView(context);
        volumeRow.addView(volumeIcon);
        volume = new SeekBar(context);
        volume.setMax(100);
        volume.setProgress(volumePercent);
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) safely(() -> callback.volumeChanged(progress, false));
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {}

            @Override public void onStopTrackingTouch(SeekBar bar) {
                safely(() -> callback.volumeChanged(bar.getProgress(), true));
            }
        });
        volumeRow.addView(volume, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        bottom.addView(volumeRow, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        bottom.addView(buttons, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        addView(bottom, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        applyTheme(theme);
    }

    void applyTheme(KsTheme theme) {
        this.theme = theme;
        int pad = theme.px(24);
        ((View) title.getParent()).setPadding(pad, pad, pad, 0);
        buttons.setPadding(pad, 0, pad, theme.px(32));
        volumeRow.setPadding(theme.px(40), 0, theme.px(40), theme.px(20));
        Drawable speakerIcon = icon("mdi:volume-high");
        if (speakerIcon != null) speakerIcon.setTint(Color.WHITE);
        volumeIcon.setImageDrawable(speakerIcon);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(theme.px(28), theme.px(28));
        iconParams.rightMargin = theme.px(12);
        volumeIcon.setLayoutParams(iconParams);
        try {
            theme.styleSlider(volume);
        } catch (RuntimeException | LinkageError older) {
            // A stock slider.
        }
        theme.styleText(title, 22, 500, "onSurface");
        title.setTextColor(Color.WHITE);
        title.setShadowLayer(theme.px(4), 0, 0, Color.BLACK);
        theme.styleText(speaking, 15, 500, "onPrimary");
        GradientDrawable chip = new GradientDrawable();
        chip.setCornerRadius(theme.px(16));
        chip.setColor(safeColor("error", 0xFFB3261E));
        speaking.setBackground(chip);
        speaking.setPadding(theme.px(12), theme.px(6), theme.px(14), theme.px(6));
        Drawable mic = icon("mdi:microphone");
        if (mic != null) {
            mic.setBounds(0, 0, theme.px(18), theme.px(18));
            mic.setTint(Color.WHITE);
            speaking.setCompoundDrawables(mic, null, null, null);
            speaking.setCompoundDrawablePadding(theme.px(6));
        }
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        chipParams.topMargin = theme.px(12);
        speaking.setLayoutParams(chipParams);
        theme.styleText(placeholder, 16, 400, "onSurfaceVariant");
        placeholder.setTextColor(0xFFBDBDBD);
        if (placeholder.getText().length() == 0) placeholder.setText(texts.connecting());
        render();
    }

    /** Shows the auto-close countdown as a ring around Hang up; durationMillis 0 removes it. */
    void setCountdown(long startedAt, long durationMillis) {
        countdownStart = startedAt;
        countdownMillis = durationMillis;
        invalidateRings(this);
    }

    private static void invalidateRings(View view) {
        if (view instanceof CountdownRing) view.invalidate();
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) invalidateRings(group.getChildAt(i));
        }
    }

    /** A ring that empties over the countdown, drawn around the Hang up circle. */
    private final class CountdownRing extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint progress = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();

        /** color is the button's own fill, so the ring reads as part of it. */
        CountdownRing(Context context, int color) {
            super(context);
            float stroke = theme.px(2);
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeWidth(stroke);
            track.setColor((color & 0x00FFFFFF) | 0x40000000);
            progress.setStyle(Paint.Style.STROKE);
            progress.setStrokeWidth(stroke);
            progress.setStrokeCap(Paint.Cap.ROUND);
            progress.setColor(color);
        }

        @Override protected void onDraw(Canvas canvas) {
            if (countdownMillis <= 0) return;
            float left = System.currentTimeMillis() - countdownStart;
            float remaining = Math.max(0f, Math.min(1f, 1f - left / countdownMillis));
            float inset = progress.getStrokeWidth() / 2;
            bounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
            canvas.drawOval(bounds, track);
            // From twelve o'clock, emptying clockwise as the time runs out.
            canvas.drawArc(bounds, -90 + 360 * (1 - remaining), 360 * remaining, false, progress);
            if (remaining > 0) postInvalidateOnAnimation();
        }
    }

    /** The decoded picture's size, to fit it in the view without stretching. */
    void setVideoSize(int width, int height) {
        videoWidth = width;
        videoHeight = height;
        fit();
    }

    /** Shown over the picture area while there is no picture, such as when the stream fails. */
    void setMessage(String message) {
        if (pictureShown) return;
        placeholder.setText(message);
        placeholder.setVisibility(VISIBLE);
    }

    /** Scales the picture to the largest size that shows all of it, centred. */
    private void fit() {
        int width = picture.getWidth(), height = picture.getHeight();
        if (width == 0 || height == 0 || videoWidth == 0 || videoHeight == 0) return;
        float viewAspect = width / (float) height, videoAspect = videoWidth / (float) videoHeight;
        float scaleX = videoAspect > viewAspect ? 1f : videoAspect / viewAspect;
        float scaleY = videoAspect > viewAspect ? viewAspect / videoAspect : 1f;
        Matrix matrix = new Matrix();
        matrix.setScale(scaleX, scaleY, width / 2f, height / 2f);
        picture.setTransform(matrix);
    }

    void update(State state, boolean speakerOn, boolean micOn) {
        this.state = state;
        this.speakerOn = speakerOn;
        this.micOn = micOn;
        render();
    }

    private void render() {
        // Just the name: the chime, the picture and the buttons already say what is going on.
        title.setText(name);
        speaking.setVisibility(state == State.IN_CALL && micOn ? VISIBLE : GONE);
        speaking.setText(texts.microphoneActive());
        volumeRow.setVisibility(speakerOn ? VISIBLE : GONE);
        // Every position keeps one job: left answers, then is the microphone; middle is the speaker; right hangs up.
        // Speaker and microphone are toggles: the same icon and label, the circle shows whether they are on.
        buttons.removeAllViews();
        if (state == State.IN_CALL) addButton("mdi:microphone", texts.microphone(), Style.TOGGLE, micOn, callback::toggleMic);
        else addButton("mdi:phone", texts.answer(), Style.ANSWER, false, callback::answer);
        addButton("mdi:volume-high", texts.speaker(), Style.TOGGLE, speakerOn, state == State.RINGING ? callback::speaker : callback::toggleSpeaker);
        addButton("mdi:phone-hangup", state == State.IN_CALL ? texts.endCall() : texts.hangUp(), Style.HANG_UP, false, callback::hangUp);
    }

    private enum Style { ANSWER, TOGGLE, HANG_UP }

    private void addButton(String icon, String label, Style style, boolean active, Runnable action) {
        Context context = getContext();
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        int size = theme.px(76);
        int fill, iconColor = Color.WHITE;
        switch (style) {
            case ANSWER: fill = safeColor("success", 0xFF2E7D32); break;
            case HANG_UP: fill = safeColor("error", 0xFFB3261E); break;
            default:
                fill = active ? Color.WHITE : 0x59FFFFFF;
                iconColor = active ? 0xFF202124 : Color.WHITE;
        }
        TextView circle = new TextView(context);
        GradientDrawable round = new GradientDrawable();
        round.setShape(GradientDrawable.OVAL);
        round.setColor(fill);
        circle.setBackground(round);
        Drawable drawable = icon(icon);
        if (drawable != null) {
            drawable.setBounds(0, 0, theme.px(34), theme.px(34));
            drawable.setTint(iconColor);
            circle.setCompoundDrawables(drawable, null, null, null);
            circle.setPadding((size - theme.px(34)) / 2, 0, 0, 0);
        }
        circle.setGravity(Gravity.CENTER_VERTICAL);
        circle.setContentDescription(style == Style.TOGGLE ? label + ", " + (active ? texts.on() : texts.off()) : label);
        // Every button gets the same outer frame, so the row lines up; Hang up draws the countdown ring in it.
        int gap = theme.px(8);
        FrameLayout frame = new FrameLayout(context);
        if (style == Style.HANG_UP) frame.addView(new CountdownRing(context, fill), new LayoutParams(size + 2 * gap, size + 2 * gap));
        LayoutParams circleParams = new LayoutParams(size, size, Gravity.CENTER);
        frame.addView(circle, circleParams);
        column.addView(frame, new LinearLayout.LayoutParams(size + 2 * gap, size + 2 * gap));
        TextView caption = new TextView(context);
        theme.styleText(caption, 14, 500, "onSurface");
        caption.setTextColor(Color.WHITE);
        caption.setShadowLayer(theme.px(3), 0, 0, Color.BLACK);
        caption.setText(label);
        caption.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams captionParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        captionParams.topMargin = theme.px(2);
        column.addView(caption, captionParams);
        column.setOnClickListener(v -> safely(action));
        circle.setOnClickListener(v -> safely(action));
        buttons.addView(column, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) safely(callback::touched);
        return false;
    }

    /** Click handlers run outside the host's guards: an exception there would end the app. */
    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // The session may have ended; the overlay goes away with it.
        }
    }

    private Drawable icon(String name) {
        try {
            return theme.icon(name, "onSurface");
        } catch (RuntimeException | LinkageError older) {
            return null;
        }
    }

    private int safeColor(String role, int fallback) {
        try {
            return theme.color(role);
        } catch (RuntimeException | LinkageError unknown) {
            return fallback;
        }
    }
}
