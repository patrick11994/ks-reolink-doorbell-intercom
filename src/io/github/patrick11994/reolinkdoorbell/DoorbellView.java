// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.jxl.kiosk.plugins.KsTheme;
import io.github.patrick11994.reolinkdoorbell.RingController.State;

/**
 * The full-screen doorbell viewer: the live picture (a TextureView, which KS overlays support), the doorbell's name,
 * a volume slider while door audio plays (the panel has no volume buttons), the call buttons, and the user's cards
 * (Home Assistant actions and states, see Cards).
 * Portrait puts the name above the picture and the controls below it, in the space a 4:3 picture leaves, with the
 * cards right above the call buttons. Landscape has no such space, so the controls move to a column on the right,
 * the cards to one on the left, and the picture fits in between, uncovered.
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
        /** A card was tapped, or held for one that asks for it; the plugin runs its action and reports back. */
        void cardPressed(Cards.Card card);
        /** Push to talk: the button went down (talk) or came up (stop). */
        void holdToTalk(boolean pressed);
    }

    private static final long HOLD_MILLIS = 800;
    private static final long FEEDBACK_MILLIS = 2500;
    /** How long a card with a state waits for it to change after its action ran. */
    private static final long STATE_WAIT_MILLIS = 5000;
    static final String RUNNING = "…";

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
    private final LinearLayout topBox, bottomBox, side;  // portrait: above and below the picture; landscape: the column
    private final View spacer;
    private final LinearLayout cardBox;
    private final ScrollView cardSide;  // landscape: the cards' column
    private List<Cards.Card> cards = Collections.emptyList();
    private final List<CardHolder> cardHolders = new ArrayList<>();
    private final Map<String, Map<?, ?>> snapshots = new HashMap<>();
    private boolean landscape;
    private boolean arranged;
    private int buttonDp = 76;
    private static final int SIDE_DP = 160;
    private static final int CARD_DP = 64, CARD_GAP_DP = 8;
    /** Landscape cards join the button column only if the buttons can stay at least this big. */
    private static final int MIN_BUTTON_WITH_CARDS_DP = 64;
    private boolean cardsRight;  // landscape: the cards above the buttons, instead of in their own column on the left
    // Landscape, by what the column measures once Kiosk Satellite has drawn it in its own font and scale: 0 the
    // buttons stacked, 1 Speaker above Answer (later Microphone) and Hang up side by side, 2 the same without labels.
    private int columnLevel;
    private static final int LOW_BUTTON_DP = 56;
    private int columnPx;  // landscape: each column's width, SIDE_DP plus its share of what the picture leaves
    // A nearly square screen (an NSPanel Pro) has no room beside the picture: it fills the screen, the controls float
    // over it on dark gradients, the cards become a row of round buttons, and during a call the controls hide.
    private boolean overlay;
    private static final float OVERLAY_ASPECT = 1.3f;  // portrait screens lower than this many times their width
    private static final int OVERLAY_BUTTON_DP = 56, ROUND_CARD_DP = 52;
    private static final long HIDE_MILLIS = 3000;
    // Answered, the visitor matters most: anything for the cards was done while it rang, so they go almost at once.
    private static final long HIDE_AFTER_ANSWER_MILLIS = 1000;
    private boolean controlsHidden;
    private final TextView hint;  // overlay: what a round card has to say, which it has no room for
    private final FrameLayout topRow;  // overlay: the name on the left, the chip on the right
    private final Runnable hideControls = () -> setControlsHidden(true);
    private State state;
    private boolean speakerOn;
    private boolean micOn;
    private boolean pushToTalk;  // the Talk mode setting: in a call the microphone button is held to talk
    private boolean talking;  // push to talk is held right now
    private boolean renderPending;  // the buttons changed while Hold to talk was held
    private boolean arrangePending, pendingWide;  // the layout changed while Hold to talk was held
    private long talkDownAt;
    private boolean talkHint;  // the chip shows how push to talk works, after a tap too short to say anything
    private static final long SHORT_TAP_MILLIS = 400;
    private final Runnable endTalkHint = this::endTalkHint;

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

        title = new TextView(context);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        speaking = new TextView(context);
        speaking.setGravity(Gravity.CENTER);
        // Measured and drawn at the same precision: at 180 dpi the hinted text came out wider than measured, clipping
        // the chip's last letter.
        speaking.getPaint().setSubpixelText(true);
        speaking.setVisibility(GONE);
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
        buttons = new LinearLayout(context);
        buttons.setGravity(Gravity.CENTER);
        topBox = new LinearLayout(context);
        topBox.setOrientation(LinearLayout.VERTICAL);
        topBox.setGravity(Gravity.CENTER_HORIZONTAL);
        bottomBox = new LinearLayout(context);
        bottomBox.setOrientation(LinearLayout.VERTICAL);
        side = new LinearLayout(context);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setGravity(Gravity.CENTER_HORIZONTAL);
        spacer = new View(context);
        cardBox = new LinearLayout(context);
        cardBox.setOrientation(LinearLayout.VERTICAL);
        topRow = new FrameLayout(context);
        hint = new TextView(context);
        hint.setGravity(Gravity.CENTER);
        hint.setVisibility(GONE);
        cardSide = new ScrollView(context);
        cardSide.setVerticalScrollBarEnabled(false);
        // The picture fills what the name and the controls leave, so the cards never cover it.
        OnLayoutChangeListener boxes = (v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot) post(this::placeVideo);
        };
        topBox.addOnLayoutChangeListener(boxes);
        bottomBox.addOnLayoutChangeListener(boxes);

        arrange(false);
        applyTheme(theme);
    }

    /** Portrait or landscape follows the view's own shape, so a rotation or a KS rebuild rearranges it. */
    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        boolean wide = width > height;
        boolean right = wide && cardsFitRight(height);
        boolean square = !wide && height < width * OVERLAY_ASPECT;
        int size = square ? OVERLAY_BUTTON_DP : buttonDpFor(wide, height, right);
        if (wide != landscape || size != buttonDp || right != cardsRight || square != overlay || !arranged || height != oldHeight) {
            buttonDp = size;
            cardsRight = right;
            overlay = square;
            columnLevel = 0;
            post(() -> {
                arrange(wide);
                applyPadding();
                render();
            });
        }
    }

    /** Buttons shrink a little on a low landscape screen, so all three always fit in the column. */
    private int buttonDpFor(boolean wide, int heightPx, boolean withCards) {
        if (!wide) return 76;
        float available = columnSpareDp(heightPx) - (withCards ? cardsDp() : 0);
        return Math.max(48, Math.min(76, Math.round(available / 3 - 36)));  // each button: circle + 16 ring + 20 label
    }

    /** The landscape column's height left for the buttons and cards: less the name, the padding, the volume slider. */
    private float columnSpareDp(int heightPx) {
        // In Kiosk Satellite's own dp, which everything here is drawn in: its UI scale can be larger than Android's
        // density (an Echo Show), and buttons sized in Android's dp then ran off the bottom of the column.
        return heightPx / (theme.px(1000) / 1000f) - 54 - 48 - 64;
    }

    private float cardsDp() {
        return cards.size() * (CARD_DP + CARD_GAP_DP) + 4;
    }

    /**
     * Landscape: the cards go above the buttons when all of them fit there with the buttons still a good size, which
     * gives the picture the left column's width. On a lower screen they keep their own column on the left.
     */
    private boolean cardsFitRight(int heightPx) {
        return !cards.isEmpty() && columnSpareDp(heightPx) - cardsDp() >= 3 * (MIN_BUTTON_WITH_CARDS_DP + 36);
    }

    /** Places the name, chip, slider and buttons for portrait or landscape. */
    private void arrange(boolean wide) {
        // Moving the buttons would detach Hold to talk from under the finger, which cancels the hold: wait.
        if (talking) {
            arrangePending = true;
            pendingWide = wide;
            return;
        }
        arranged = getWidth() > 0;
        landscape = wide;
        for (View piece : new View[] {title, speaking, volumeRow, buttons, spacer, cardBox, hint, topRow}) detach(piece);
        title.setMaxWidth(Integer.MAX_VALUE);
        topBox.setBackground(null);
        bottomBox.setBackground(null);
        removeView(topBox);
        removeView(bottomBox);
        removeView(side);
        removeView(cardSide);
        LayoutParams videoParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        int fill = LinearLayout.LayoutParams.MATCH_PARENT, wrap = LinearLayout.LayoutParams.WRAP_CONTENT;
        if (wide) {
            columnPx = columnPx();
            videoParams.rightMargin = columnPx;
            title.setGravity(Gravity.CENTER);
            side.addView(title, new LinearLayout.LayoutParams(fill, wrap));
            side.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));
            if (cardsRight) side.addView(cardBox, new LinearLayout.LayoutParams(fill, wrap));
            side.addView(volumeRow, new LinearLayout.LayoutParams(fill, wrap));
            side.addView(buttons, new LinearLayout.LayoutParams(fill, wrap));
            buttons.setOrientation(LinearLayout.VERTICAL);
            addView(side, new LayoutParams(columnPx, LayoutParams.MATCH_PARENT, Gravity.END));
            if (!cards.isEmpty() && !cardsRight) {
                videoParams.leftMargin = columnPx;
                cardSide.addView(cardBox, new ScrollView.LayoutParams(fill, wrap));
                addView(cardSide, new LayoutParams(columnPx, LayoutParams.MATCH_PARENT, Gravity.START));
            }
            // The speaking chip floats at the top middle of the picture, on one line.
            LayoutParams chip = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            chip.topMargin = theme.px(12);
            // A centred child moves by its whole left margin minus its right one, so half of each centres it on the picture.
            chip.leftMargin = videoParams.leftMargin / 2;
            chip.rightMargin = videoParams.rightMargin / 2;
            addView(speaking, chip);
        } else if (overlay) {
            // The name on the left and the chip on the right: the chip never lands on the cards, and stays put when
            // the name fades out.
            // In a frame rather than a row: a horizontal LinearLayout measured the chip a little short, clipping it.
            topBox.setOrientation(LinearLayout.VERTICAL);
            title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            title.setMaxWidth(getWidth() / 2);
            topRow.addView(title, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL));
            topRow.addView(speaking, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL));
            topBox.addView(topRow, new LinearLayout.LayoutParams(fill, wrap));
            topBox.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0xB3000000, 0x00000000}));
            addView(topBox, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP));
            LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(wrap, wrap);
            hintParams.gravity = Gravity.CENTER_HORIZONTAL;
            hintParams.bottomMargin = theme.px(8);
            bottomBox.addView(hint, hintParams);
            bottomBox.addView(cardBox, new LinearLayout.LayoutParams(fill, wrap));
            bottomBox.addView(volumeRow, new LinearLayout.LayoutParams(fill, wrap));
            bottomBox.addView(buttons, new LinearLayout.LayoutParams(fill, wrap));
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            bottomBox.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[] {0xCC000000, 0x99000000, 0x00000000}));
            addView(bottomBox, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        } else {
            topBox.setOrientation(LinearLayout.VERTICAL);
            topBox.setGravity(Gravity.CENTER_HORIZONTAL);
            title.setGravity(Gravity.CENTER);
            topBox.addView(title, new LinearLayout.LayoutParams(fill, wrap));
            addView(topBox, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP));
            // The chip floats just under the name, over the picture's edge, and takes no room: nothing moves when the
            // microphone goes on or off.
            addView(speaking, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
            bottomBox.addView(cardBox, new LinearLayout.LayoutParams(fill, wrap));
            bottomBox.addView(volumeRow, new LinearLayout.LayoutParams(fill, wrap));
            bottomBox.addView(buttons, new LinearLayout.LayoutParams(fill, wrap));
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            addView(bottomBox, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        }
        video.setLayoutParams(videoParams);
        if (!overlay && controlsHidden) {
            // Out of the overlay layout, such as after a rotation: everything shows again.
            controlsHidden = false;
            title.setVisibility(VISIBLE);
            buttons.setVisibility(VISIBLE);
        }
        buildCards();
        placeVideo();
        if (overlay) scheduleHide();  // also for a viewer that opens straight into a call
        else removeCallbacks(hideControls);
    }

    /**
     * Landscape: a picture that already fills the height leaves black bars beside it, so the columns take that width
     * instead, shared when there are two. The picture's own shape once the decoder knows it, 4:3 until then.
     */
    private int columnPx() {
        int columns = !cards.isEmpty() && !cardsRight ? 2 : 1;
        float aspect = videoWidth > 0 && videoHeight > 0 ? videoWidth / (float) videoHeight : 4f / 3f;
        int spare = getWidth() - columns * theme.px(SIDE_DP) - Math.round(getHeight() * aspect);
        return theme.px(SIDE_DP) + Math.max(0, spare) / columns;
    }

    /** Portrait: the picture between the name and the controls, so the cards and buttons never cover it. */
    private void placeVideo() {
        if (landscape) return;
        if (!overlay && speaking.getLayoutParams() instanceof LayoutParams) {
            LayoutParams chip = (LayoutParams) speaking.getLayoutParams();
            int below = topBox.getHeight() + theme.px(4);
            if (chip.topMargin != below) {
                chip.topMargin = below;
                speaking.setLayoutParams(chip);
            }
        }
        LayoutParams params = (LayoutParams) video.getLayoutParams();
        int top = topBox.getHeight(), bottom = bottomBox.getHeight();
        // The overlay layout, or a screen too low for both, gets the picture behind the controls.
        if (overlay || getHeight() - top - bottom < getHeight() / 3) top = bottom = 0;
        if (params.topMargin == top && params.bottomMargin == bottom) return;
        params.topMargin = top;
        params.bottomMargin = bottom;
        video.setLayoutParams(params);
    }

    private static void detach(View view) {
        if (view.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) view.getParent()).removeView(view);
    }

    private LinearLayout.LayoutParams chipParams() {
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        chipParams.topMargin = theme.px(12);
        chipParams.gravity = Gravity.CENTER_HORIZONTAL;
        return chipParams;
    }

    private void applyPadding() {
        int pad = theme.px(24);
        if (landscape) {
            side.setPadding(theme.px(8), pad, theme.px(8), pad);
            if (cardsRight) cardBox.setPadding(0, 0, 0, theme.px(12));
            else cardBox.setPadding(theme.px(8), pad, theme.px(8), pad);
            buttons.setPadding(0, 0, 0, 0);
            volumeRow.setPadding(theme.px(4), 0, theme.px(4), theme.px(16));
        } else if (overlay) {
            topBox.setPadding(pad, theme.px(16), pad, theme.px(28));
            bottomBox.setPadding(0, theme.px(40), 0, 0);
            cardBox.setPadding(pad, 0, pad, theme.px(12));
            buttons.setPadding(theme.px(12), 0, theme.px(12), theme.px(16));
            volumeRow.setPadding(theme.px(32), 0, theme.px(32), theme.px(8));
        } else {
            topBox.setPadding(pad, pad, pad, 0);
            bottomBox.setPadding(0, 0, 0, 0);
            cardBox.setPadding(pad, theme.px(16), pad, theme.px(8));
            buttons.setPadding(pad, 0, pad, theme.px(32));
            volumeRow.setPadding(theme.px(40), 0, theme.px(40), theme.px(20));
        }
    }

    void applyTheme(KsTheme theme) {
        this.theme = theme;
        applyPadding();
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
        theme.styleText(hint, 14, 500, "onSurface");
        hint.setTextColor(Color.WHITE);
        GradientDrawable hintPill = new GradientDrawable();
        hintPill.setCornerRadius(theme.px(14));
        hintPill.setColor(0xCC202124);
        hint.setBackground(hintPill);
        hint.setPadding(theme.px(12), theme.px(5), theme.px(12), theme.px(5));
        theme.styleText(placeholder, 16, 400, "onSurfaceVariant");
        placeholder.setTextColor(0xFFBDBDBD);
        if (placeholder.getText().length() == 0) placeholder.setText(texts.connecting());
        buildCards();
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
        if (landscape && arranged && columnPx() != columnPx) {
            arrange(true);  // a picture of another shape leaves other bars
            applyPadding();
            render();
        }
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

    void setPushToTalk(boolean pushToTalk) {
        this.pushToTalk = pushToTalk;
        render();
    }

    void update(State state, boolean speakerOn, boolean micOn) {
        boolean changed = state != this.state;
        boolean answered = changed && this.state == State.RINGING;
        this.state = state;
        if (state == State.IDLE) talking = false;  // the plugin stops push to talk with the call
        this.speakerOn = speakerOn;
        this.micOn = micOn;
        render();
        if (changed) setControlsHidden(false);  // a new state shows what it offers, then hides again in a call
        if (answered && overlay) {
            removeCallbacks(hideControls);
            postDelayed(hideControls, HIDE_AFTER_ANSWER_MILLIS);
        }
    }

    private void render() {
        // Just the name: the chime, the picture and the buttons already say what is going on.
        title.setText(name);
        if (!talkHint) {
            speaking.setVisibility(speakingVisibility());
            speaking.setText(texts.microphoneActive());
        }
        // The slider's room stays, so nothing jumps when the speaker turns on: not the picture in portrait, nor the cards
        // and buttons in the landscape column (whose button size already counts the slider). The overlay has nothing
        // to keep still, so there the cards sit on the buttons until the slider comes.
        boolean opens = overlay && speakerOn && volumeRow.getVisibility() == GONE;
        boolean closes = overlay && !speakerOn && volumeRow.getVisibility() != GONE;
        if (opens || closes) slideVolume(opens);
        else if (speakerOn) volumeRow.setVisibility(controlsHidden ? INVISIBLE : VISIBLE);
        else volumeRow.setVisibility(overlay ? GONE : INVISIBLE);
        // Rebuilding the buttons would take Hold to talk away from under the finger, and lose its release: wait.
        if (talking) {
            renderPending = true;
            return;
        }
        // Every position keeps one job: left (top) answers, then is the microphone; middle is the speaker; right
        // (bottom) hangs up.
        // Speaker and microphone are toggles: the same icon and label, the circle shows whether they are on.
        buttons.removeAllViews();
        // Push to talk: holding answers and talks at once, so the button is there from the ring on.
        View first = pushToTalk
            ? holdButton()
            : state == State.IN_CALL
                ? makeButton("mdi:microphone", texts.microphone(), Style.TOGGLE, micOn, callback::toggleMic)
                : makeButton("mdi:phone", texts.answer(), Style.ANSWER, false, callback::answer);
        View speaker = makeButton("mdi:volume-high", texts.speaker(), Style.TOGGLE, speakerOn, state == State.RINGING ? callback::speaker : callback::toggleSpeaker);
        // Push to talk has no ringing screen of its own: Hold to talk answers, so End call is what this button does.
        View last = makeButton("mdi:phone-hangup", state == State.IN_CALL || pushToTalk ? texts.endCall() : texts.hangUp(), Style.HANG_UP, false, callback::hangUp);
        int wrap = LinearLayout.LayoutParams.WRAP_CONTENT, fill = LinearLayout.LayoutParams.MATCH_PARENT;
        if (landscape && columnLevel >= 1) {
            // A low column: Speaker on top, the other two side by side, each keeping its place from the other layouts.
            buttons.setOrientation(LinearLayout.VERTICAL);
            LinearLayout top = new LinearLayout(getContext());
            top.setGravity(Gravity.CENTER_HORIZONTAL);
            top.addView(speaker, new LinearLayout.LayoutParams(wrap, wrap));
            LinearLayout bottom = new LinearLayout(getContext());
            bottom.setOrientation(LinearLayout.HORIZONTAL);
            bottom.addView(first, new LinearLayout.LayoutParams(0, wrap, 1));
            bottom.addView(last, new LinearLayout.LayoutParams(0, wrap, 1));
            buttons.addView(top, new LinearLayout.LayoutParams(fill, wrap));
            buttons.addView(bottom, new LinearLayout.LayoutParams(fill, wrap));
        } else {
            for (View button : new View[] {first, speaker, last}) {
                LinearLayout.LayoutParams place = landscape ? new LinearLayout.LayoutParams(fill, wrap) : new LinearLayout.LayoutParams(0, wrap, 1);
                if (landscape) place.topMargin = theme.px(4);
                buttons.addView(button, place);
            }
        }
        if (controlsHidden) buttons.setVisibility(INVISIBLE);
        if (landscape) post(this::fitColumn);
        if (landscape && columnLevel >= 1) evenLabelsWhenLaidOut();
    }

    /** Every text a button's place shows in any state, so its label gets one size from the ring through the call. */
    private String[] placeTexts(String label) {
        String[][] places = {
            {texts.answer(), texts.microphone(), texts.holdToTalk()},
            {texts.hangUp(), texts.endCall()},
            {texts.speaker()},
        };
        for (String[] place : places) {
            for (String text : place) if (text.equals(label)) return pushToTalk && place[0].equals(texts.answer()) ? new String[] {texts.holdToTalk()} : place;
        }
        return new String[] {label};
    }

    private static final float CAPTION_SP = 14, MIN_CAPTION_SP = 9;

    /**
     * The low layout's labels, side by side: if one is too wide for its button, all of them shrink by the same
     * amount, to what the widest needs, so they stay alike. Measured from the text itself, in the label's own font.
     */
    private void evenLabels() {
        List<TextView> captions = new ArrayList<>();
        collectCaptions(buttons, captions);
        if (captions.isEmpty()) return;
        float scale = 1f;
        Paint paint = new Paint();
        for (TextView caption : captions) {
            // Only the side-by-side labels have a fixed room; Speaker's on top is as wide as its own text.
            if (caption.getLayoutParams() == null || caption.getLayoutParams().width != LinearLayout.LayoutParams.MATCH_PARENT) continue;
            int room = caption.getWidth() - caption.getPaddingLeft() - caption.getPaddingRight();
            if (room <= 0) return;  // not laid out: nothing to measure
            paint.set(caption.getPaint());
            paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, CAPTION_SP, getResources().getDisplayMetrics()));
            float needed = 0;
            for (String text : (String[]) caption.getTag()) needed = Math.max(needed, paint.measureText(text));
            // A little short of the exact fit: text laid out at exactly its room still loses its last letter.
            if (needed > room * 0.94f) scale = Math.min(scale, room * 0.94f / needed);
        }
        float size = Math.max(MIN_CAPTION_SP, CAPTION_SP * scale);
        for (TextView caption : captions) caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
    }

    /** Once the new buttons have their widths: they are measured in the next layout pass, not when built. */
    private void evenLabelsWhenLaidOut() {
        buttons.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                buttons.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                evenLabels();
            }
        });
        buttons.requestLayout();
    }

    private static void collectCaptions(View view, List<TextView> into) {
        if (view instanceof TextView && view.getTag() instanceof String[]) into.add((TextView) view);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectCaptions(group.getChildAt(i), into);
        }
    }

    /**
     * Landscape: if the column does not fit the screen's height, measured as drawn, first the cards go back to their
     * own column, then the buttons take the low layout, then they lose their labels. A screen's size starts it over.
     */
    private void fitColumn() {
        if (!landscape || !arranged || getHeight() == 0) return;
        side.measure(MeasureSpec.makeMeasureSpec(side.getWidth() > 0 ? side.getWidth() : columnPx, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        if (side.getMeasuredHeight() <= getHeight()) return;
        if (cardsRight) {
            cardsRight = false;
            buttonDp = buttonDpFor(true, getHeight(), false);
            arrange(true);
            applyPadding();
        } else if (columnLevel < 2) {
            columnLevel++;
            buttonDp = LOW_BUTTON_DP;
        } else if (buttonDp > 48) {
            buttonDp = 48;
        } else {
            return;  // as small as it goes
        }
        render();
    }

    private ValueAnimator volumeSlide;

    /** The overlay's slider grows open, or closes, so the cards and the gradient above it slide instead of jumping. */
    private void slideVolume(boolean open) {
        if (volumeSlide != null) volumeSlide.cancel();
        int width = bottomBox.getWidth() > 0 ? bottomBox.getWidth() : getWidth();
        volumeRow.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int full = volumeRow.getMeasuredHeight();
        int from = open ? 0 : volumeRow.getHeight() > 0 ? volumeRow.getHeight() : full;
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) volumeRow.getLayoutParams();
        params.height = from;
        volumeRow.setLayoutParams(params);
        volumeRow.setVisibility(open && controlsHidden ? INVISIBLE : VISIBLE);
        volumeSlide = ValueAnimator.ofInt(from, open ? full : 0);
        volumeSlide.setDuration(200);
        volumeSlide.addUpdateListener(a -> {
            params.height = (int) a.getAnimatedValue();
            volumeRow.setAlpha(full == 0 ? 1f : params.height / (float) full);
            volumeRow.setLayoutParams(params);
        });
        volumeSlide.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override public void onAnimationCancel(Animator a) { cancelled = true; }

            @Override public void onAnimationEnd(Animator a) {
                if (cancelled) return;
                params.height = LinearLayout.LayoutParams.WRAP_CONTENT;
                volumeRow.setLayoutParams(params);
                volumeRow.setAlpha(1f);
                if (!open) volumeRow.setVisibility(GONE);
            }
        });
        volumeSlide.start();
    }

    /**
     * Push to talk's button: white while held, like a toggle that is on. It restyles itself instead of re-rendering,
     * which would take the view away from under the finger.
     */
    private View holdButton() {
        View column = makeButton("mdi:microphone", texts.holdToTalk(), Style.TOGGLE, talking, null);
        TextView circle = findCircle(column);
        column.setOnClickListener(null);
        column.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: setTalking(true, circle); return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: setTalking(false, circle); return true;
                default: return true;
            }
        });
        if (circle != null) {
            circle.setOnClickListener(null);
            circle.setClickable(false);
        }
        return column;
    }

    private void setTalking(boolean now, TextView circle) {
        if (talking == now) return;
        talking = now;
        if (now) {
            talkDownAt = System.currentTimeMillis();
            if (talkHint) endTalkHint();
        }
        if (circle != null) {
            ((GradientDrawable) circle.getBackground()).setColor(now ? Color.WHITE : 0x59FFFFFF);
            Drawable[] drawables = circle.getCompoundDrawables();
            if (drawables[0] != null) drawables[0].setTint(now ? 0xFF202124 : Color.WHITE);
        }
        speaking.setVisibility(speakingVisibility());
        if (!now && System.currentTimeMillis() - talkDownAt < SHORT_TAP_MILLIS) showTalkHint();
        if (now) removeCallbacks(hideControls);  // never hide what the finger is on
        else scheduleHide();
        safely(() -> callback.holdToTalk(now));
        if (!now && arrangePending) {
            arrangePending = false;
            renderPending = false;
            arrange(pendingWide);
            applyPadding();
            render();
        } else if (!now && renderPending) {
            renderPending = false;
            render();
        }
    }

    private int speakingVisibility() {
        if (pushToTalk) return talking ? VISIBLE : GONE;
        return state == State.IN_CALL && micOn ? VISIBLE : GONE;
    }

    /** A tap too short to talk: the chip says, in grey rather than the microphone's red, to hold the button. */
    private void showTalkHint() {
        removeCallbacks(endTalkHint);
        talkHint = true;
        ((GradientDrawable) speaking.getBackground()).setColor(0xE6202124);
        speaking.setText(texts.holdToTalkHint());
        speaking.setVisibility(VISIBLE);
        postDelayed(endTalkHint, FEEDBACK_MILLIS);
    }

    private void endTalkHint() {
        removeCallbacks(endTalkHint);
        talkHint = false;
        ((GradientDrawable) speaking.getBackground()).setColor(safeColor("error", 0xFFB3261E));
        speaking.setText(texts.microphoneActive());
        speaking.setVisibility(speakingVisibility());
    }

    private static TextView findCircle(View column) {
        if (!(column instanceof android.view.ViewGroup)) return null;
        android.view.ViewGroup group = (android.view.ViewGroup) column;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof FrameLayout) {
                FrameLayout frame = (FrameLayout) child;
                for (int k = 0; k < frame.getChildCount(); k++) {
                    if (frame.getChildAt(k) instanceof TextView) return (TextView) frame.getChildAt(k);
                }
            }
        }
        return null;
    }

    private enum Style { ANSWER, TOGGLE, HANG_UP }

    private View makeButton(String icon, String label, Style style, boolean active, Runnable action) {
        Context context = getContext();
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        int size = theme.px(buttonDp);
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
        caption.setTag(placeTexts(label));
        if (landscape && columnLevel >= 1) {
            // Side by side in a narrow column: one line; evenLabels shrinks all of them alike if one does not fit.
            captionParams.width = LinearLayout.LayoutParams.MATCH_PARENT;
            caption.setMaxLines(1);
            caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        }
        if (!(landscape && columnLevel >= 2)) column.addView(caption, captionParams);
        if (action != null) {
            column.setOnClickListener(v -> safely(action));
            circle.setOnClickListener(v -> safely(action));
        }
        return column;
    }

    // Cards

    /** The cards from the settings; a change of cards rearranges, since landscape gives them a column only if any. */
    void setCards(List<Cards.Card> cards) {
        boolean column = this.cards.isEmpty() != cards.isEmpty() || this.cards.size() != cards.size();
        this.cards = cards;
        if (landscape && arranged) {
            cardsRight = cardsFitRight(getHeight());
            buttonDp = buttonDpFor(true, getHeight(), cardsRight);
        }
        if (column && arranged) {
            arrange(landscape);
            applyPadding();
            render();
        } else {
            buildCards();
        }
    }

    /** A Home Assistant entity's new snapshot, from the plugin's subscription. */
    void entityChanged(String entityId, Map<?, ?> snapshot) {
        snapshots.put(entityId, snapshot);
        for (CardHolder holder : cardHolders) {
            if (!holder.card.entityId.equals(entityId)) continue;
            // The new state is the answer to a running action: no flash of the old one in between.
            if (RUNNING.equals(holder.feedback)) holder.feedback(null);
            else holder.render();
        }
    }

    /** How a card's action went: shown in place of its state for a moment; null clears it. */
    void cardFeedback(Cards.Card card, String text) {
        for (CardHolder holder : cardHolders) {
            if (holder.card.row == card.row) holder.feedback(text);
        }
    }

    private void buildCards() {
        for (CardHolder holder : cardHolders) holder.cancel();
        cardHolders.clear();
        cardBox.removeAllViews();
        cardBox.setVisibility(cards.isEmpty() ? GONE : controlsHidden ? INVISIBLE : VISIBLE);
        if (overlay) {
            // One row of round buttons, as on Apple Home's camera view: the icon's colour carries the state.
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_HORIZONTAL);
            cardBox.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            for (int i = 0; i < cards.size(); i++) {
                CardHolder holder = new CardHolder(getContext(), cards.get(i), true);
                cardHolders.add(holder);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(theme.px(ROUND_CARD_DP), theme.px(ROUND_CARD_DP));
                if (i > 0) params.leftMargin = theme.px(16);
                row.addView(holder.root, params);
                holder.render();
            }
            return;
        }
        int gap = theme.px(landscape ? 8 : 12);
        // Landscape: one per row in the column. Portrait: two per row, always half wide; an odd last one is centred.
        int perRow = landscape ? 1 : 2;
        LinearLayout row = null;
        for (int i = 0; i < cards.size(); i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setWeightSum(perRow);
                row.setGravity(Gravity.CENTER_HORIZONTAL);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                if (i > 0) rowParams.topMargin = gap;
                cardBox.addView(row, rowParams);
            }
            CardHolder holder = new CardHolder(getContext(), cards.get(i), false);
            cardHolders.add(holder);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, theme.px(64), 1);
            if (i % perRow > 0) params.leftMargin = gap;
            // A lone last card loses the same gap, so it is exactly as wide as the others.
            if (perRow == 2 && i == cards.size() - 1 && i % 2 == 0) params.leftMargin = params.rightMargin = gap / 2;
            row.addView(holder.root, params);
            holder.render();
        }
    }

    private static int tintColor(Cards.Tint tint) {
        switch (tint) {
            case AMBER: return 0xFFFFC107;
            case GREEN: return 0xFF4CAF50;
            case RED: return 0xFFF44336;
            case ORANGE: return 0xFFFF9800;
            case PURPLE: return 0xFFAB47BC;
            default: return Color.WHITE;
        }
    }

    /** One card, like Home Assistant's tile: a tinted icon circle, the label and the state; or only the circle. */
    private final class CardHolder {
        final Cards.Card card;
        final HoldFrame root;
        final ImageView icon;
        final GradientDrawable circle = new GradientDrawable();
        final TextView label, state;
        final boolean round;  // the overlay's round button: no label or state, feedback goes to the hint
        private String feedback;
        private final Runnable clearFeedback = () -> feedback(null);

        CardHolder(Context context, Cards.Card card, boolean round) {
            this.card = card;
            this.round = round;
            root = new HoldFrame(context);
            if (round) {
                root.round = true;
                root.setGravity(Gravity.CENTER);
                circle.setShape(GradientDrawable.OVAL);
                root.setBackground(circle);
                icon = new ImageView(context);
                icon.setScaleType(ImageView.ScaleType.CENTER);
                root.addView(icon, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
                label = state = null;
                listen(card);
                return;
            }
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            // The landscape column is narrow: a smaller circle and text leave the label room.
            boolean compact = landscape;
            root.setPadding(theme.px(compact ? 8 : 12), 0, theme.px(compact ? 8 : 12), 0);
            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(theme.px(16));
            background.setColor(0x26FFFFFF);
            root.setBackground(background);
            circle.setShape(GradientDrawable.OVAL);
            icon = new ImageView(context);
            icon.setBackground(circle);
            icon.setScaleType(ImageView.ScaleType.CENTER);
            root.addView(icon, new LinearLayout.LayoutParams(theme.px(compact ? 32 : 40), theme.px(compact ? 32 : 40)));
            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            label = new TextView(context);
            theme.styleText(label, compact ? 14 : 15, 600, "onSurface");
            label.setTextColor(Color.WHITE);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            state = new TextView(context);
            theme.styleText(state, compact ? 12 : 13, 400, "onSurfaceVariant");
            state.setTextColor(0xFFBDBDBD);
            state.setSingleLine(true);
            state.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(label);
            texts.addView(state);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            textParams.leftMargin = theme.px(compact ? 8 : 12);
            root.addView(texts, textParams);
            listen(card);
        }

        private void listen(Cards.Card card) {
            if (!card.action) return;
            if (card.confirm) {
                root.hold = () -> safely(() -> callback.cardPressed(card));
                root.tooShort = () -> feedback(DoorbellView.this.texts.holdToRun());
            } else {
                root.setOnClickListener(v -> safely(() -> callback.cardPressed(card)));
            }
        }

        void render() {
            Cards.Look look = Cards.look(card, snapshots.get(card.entityId), texts);
            int accent = tintColor(look.tint);
            if (round) {
                // Not by colour alone: an active state (on, locked, armed) fills the circle, an inactive one is a ring.
                boolean active = look.tint != Cards.Tint.NONE;
                circle.setColor(active ? accent : 0xB3202124);
                circle.setStroke(theme.px(2), active ? accent : 0x80FFFFFF);
                Drawable drawable = DoorbellView.this.icon(look.icon);
                if (drawable == null) drawable = DoorbellView.this.icon(card.action ? "mdi:gesture-tap" : "mdi:eye");
                if (drawable != null) {
                    drawable.setTint(active ? 0xFF202124 : Color.WHITE);
                    icon.setImageDrawable(drawable);
                }
                root.setAlpha(look.unavailable || RUNNING.equals(feedback) ? 0.5f : 1f);
                root.setContentDescription(look.label + (look.state.isEmpty() ? "" : ", " + look.state));
                return;
            }
            label.setText(look.label);
            String shown = feedback != null ? feedback : look.state;
            state.setText(shown);
            state.setVisibility(shown.isEmpty() ? GONE : VISIBLE);
            circle.setColor(look.tint == Cards.Tint.NONE ? 0x26FFFFFF : (accent & 0x00FFFFFF) | 0x40000000);
            Drawable drawable = DoorbellView.this.icon(look.icon);
            if (drawable == null) drawable = DoorbellView.this.icon(card.action ? "mdi:gesture-tap" : "mdi:eye");
            if (drawable != null) {
                drawable.setTint(accent);
                icon.setImageDrawable(drawable);
            }
            root.setAlpha(look.unavailable ? 0.5f : 1f);
            root.setContentDescription(look.label + (look.state.isEmpty() ? "" : ", " + look.state));
        }

        void feedback(String text) {
            feedback = text;
            root.removeCallbacks(clearFeedback);
            if (text != null) root.postDelayed(clearFeedback, text.equals(RUNNING) ? STATE_WAIT_MILLIS : FEEDBACK_MILLIS);
            render();
            if (round) showHint(text == null || text.equals(RUNNING) ? null : Cards.look(card, snapshots.get(card.entityId), texts).label + ": " + text);
        }

        void cancel() {
            root.removeCallbacks(clearFeedback);
            root.cancelHold();
        }
    }

    /** A card that runs only when held: a fill sweeps across it, and lifting the finger early cancels. */
    private final class HoldFrame extends LinearLayout {
        Runnable hold, tooShort;
        boolean round;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();
        private ValueAnimator animator;
        private float progress;

        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);

        HoldFrame(Context context) {
            super(context);
            fill.setColor(0x33FFFFFF);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeCap(Paint.Cap.ROUND);
            ring.setColor(Color.WHITE);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (hold == null) return super.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    cancelHold();
                    animator = ValueAnimator.ofFloat(0f, 1f);
                    animator.setDuration(HOLD_MILLIS);
                    animator.addUpdateListener(a -> {
                        progress = (float) a.getAnimatedValue();
                        invalidate();
                    });
                    animator.addListener(new AnimatorListenerAdapter() {
                        private boolean cancelled;

                        @Override public void onAnimationCancel(Animator a) { cancelled = true; }

                        @Override public void onAnimationEnd(Animator a) {
                            progress = 0;
                            invalidate();
                            if (!cancelled) hold.run();
                        }
                    });
                    animator.start();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (animator != null && animator.isRunning()) {
                        cancelHold();
                        tooShort.run();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    cancelHold();
                    return true;
                default:
                    return true;
            }
        }

        void cancelHold() {
            if (animator != null) animator.cancel();
            animator = null;
            progress = 0;
            invalidate();
        }

        @Override protected void dispatchDraw(Canvas canvas) {
            if (progress > 0 && round) {
                // A round button fills a ring around itself, clockwise from the top, like the countdown around Hang up.
                super.dispatchDraw(canvas);
                ring.setStrokeWidth(theme.px(3));
                float inset = ring.getStrokeWidth() / 2;
                bounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
                canvas.drawArc(bounds, -90, 360 * progress, false, ring);
                return;
            }
            if (progress > 0) {
                float radius = theme.px(16);
                bounds.set(0, 0, getWidth() * progress, getHeight());
                canvas.save();
                canvas.clipRect(bounds);
                bounds.set(0, 0, getWidth(), getHeight());
                canvas.drawRoundRect(bounds, radius, radius, fill);
                canvas.restore();
            }
            super.dispatchDraw(canvas);
        }
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_DOWN) return false;
        safely(callback::touched);
        if (controlsHidden) {
            // Like FaceTime: the first tap only brings the controls back, a second one on End call hangs up.
            setControlsHidden(false);
            return true;
        }
        // Like a video player: a tap on the picture itself, away from every control, hides them at once.
        if (overlay && state != State.RINGING && !controlsHidden && !inside(buttons, event) && !inside(cardBox, event)
            && !inside(volumeRow, event) && !inside(hint, event) && !inside(speaking, event)) {
            setControlsHidden(true);
            return true;
        }
        scheduleHide();
        return false;
    }

    private static boolean inside(View view, MotionEvent event) {
        if (view == null || view.getVisibility() != VISIBLE || view.getParent() == null) return false;
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        float x = event.getRawX(), y = event.getRawY();
        return x >= at[0] && x < at[0] + view.getWidth() && y >= at[1] && y < at[1] + view.getHeight();
    }

    /** The overlay hides everything but the speaking chip during a call, as video calls do. */
    private void setControlsHidden(boolean hidden) {
        removeCallbacks(hideControls);
        boolean can = overlay && state != State.RINGING && !talking;
        hidden = hidden && can;
        if (hidden != controlsHidden) {
            controlsHidden = hidden;
            for (View view : new View[] {title, cardBox, volumeRow, hint, buttons}) fade(view, !hidden);
            if (topBox.getBackground() != null) topBox.getBackground().setAlpha(hidden && overPicture(title) ? 0 : 255);
            if (!hidden) render();
        }
        if (!hidden) scheduleHide();
    }

    /** Whether a view covers part of the picture, which fills the screen at its own shape (4:3 until known). */
    private boolean overPicture(View view) {
        float aspect = videoWidth > 0 && videoHeight > 0 ? videoWidth / (float) videoHeight : 4f / 3f;
        float width = Math.min(getWidth(), getHeight() * aspect), height = width / aspect;
        float left = (getWidth() - width) / 2, top = (getHeight() - height) / 2;
        int[] mine = new int[2], its = new int[2];
        getLocationInWindow(mine);
        view.getLocationInWindow(its);
        float x = its[0] - mine[0], y = its[1] - mine[1];
        return x < left + width && x + view.getWidth() > left && y < top + height && y + view.getHeight() > top;
    }

    private void scheduleHide() {
        removeCallbacks(hideControls);
        if (overlay && state != State.RINGING && !controlsHidden) postDelayed(hideControls, HIDE_MILLIS);
    }

    private void fade(View view, boolean show) {
        if (view.getVisibility() == GONE) return;
        if (!show && !overPicture(view)) return;  // in a black bar it hides nothing
        view.animate().cancel();
        if (show) {
            if (view == volumeRow && !speakerOn) return;
            view.setVisibility(VISIBLE);
            view.setAlpha(0f);
            view.animate().alpha(1f).setDuration(200).start();
        } else {
            view.animate().alpha(0f).setDuration(300).withEndAction(() -> {
                view.setVisibility(INVISIBLE);
                view.setAlpha(1f);
            }).start();
        }
    }

    private final Runnable hideHint = this::hideHintNow;

    private void hideHintNow() {
        hint.setVisibility(GONE);
    }

    /** A round card's feedback, such as "Front door: Hold to run", for a moment above the row; null hides it. */
    private void showHint(String text) {
        removeCallbacks(hideHint);
        if (text == null) {
            hint.setVisibility(GONE);
            return;
        }
        hint.setText(text);
        hint.setVisibility(VISIBLE);
        postDelayed(hideHint, FEEDBACK_MILLIS);
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
