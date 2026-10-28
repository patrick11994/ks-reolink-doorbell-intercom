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

        title = new TextView(context);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        speaking = new TextView(context);
        speaking.setGravity(Gravity.CENTER);
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
        int size = buttonDpFor(wide, height);
        if (wide != landscape || size != buttonDp || !arranged) {
            buttonDp = size;
            post(() -> {
                arrange(wide);
                applyPadding();
                render();
            });
        }
    }

    /** Buttons shrink a little on a low landscape screen, so all three always fit in the column. */
    private int buttonDpFor(boolean wide, int heightPx) {
        if (!wide) return 76;
        float density = getResources().getDisplayMetrics().density;
        float available = heightPx / density - 54 - 48 - 64;  // the name, the column's padding, the volume slider
        return Math.max(56, Math.min(76, Math.round(available / 3 - 36)));  // each button: circle + 16 ring + 20 label
    }

    /** Places the name, chip, slider and buttons for portrait or landscape. */
    private void arrange(boolean wide) {
        arranged = getWidth() > 0;
        landscape = wide;
        for (View piece : new View[] {title, speaking, volumeRow, buttons, spacer, cardBox}) detach(piece);
        removeView(topBox);
        removeView(bottomBox);
        removeView(side);
        removeView(cardSide);
        LayoutParams videoParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        int fill = LinearLayout.LayoutParams.MATCH_PARENT, wrap = LinearLayout.LayoutParams.WRAP_CONTENT;
        if (wide) {
            videoParams.rightMargin = theme.px(SIDE_DP);
            side.addView(title, new LinearLayout.LayoutParams(fill, wrap));
            side.addView(speaking, chipParams());
            side.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));
            side.addView(volumeRow, new LinearLayout.LayoutParams(fill, wrap));
            side.addView(buttons, new LinearLayout.LayoutParams(fill, wrap));
            buttons.setOrientation(LinearLayout.VERTICAL);
            addView(side, new LayoutParams(theme.px(SIDE_DP), LayoutParams.MATCH_PARENT, Gravity.END));
            if (!cards.isEmpty()) {
                videoParams.leftMargin = theme.px(SIDE_DP);
                cardSide.addView(cardBox, new ScrollView.LayoutParams(fill, wrap));
                addView(cardSide, new LayoutParams(theme.px(SIDE_DP), LayoutParams.MATCH_PARENT, Gravity.START));
            }
        } else {
            topBox.addView(title, new LinearLayout.LayoutParams(fill, wrap));
            topBox.addView(speaking, chipParams());
            addView(topBox, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP));
            bottomBox.addView(cardBox, new LinearLayout.LayoutParams(fill, wrap));
            bottomBox.addView(volumeRow, new LinearLayout.LayoutParams(fill, wrap));
            bottomBox.addView(buttons, new LinearLayout.LayoutParams(fill, wrap));
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            addView(bottomBox, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        }
        video.setLayoutParams(videoParams);
        buildCards();
        placeVideo();
    }

    /** Portrait: the picture between the name and the controls, so the cards and buttons never cover it. */
    private void placeVideo() {
        if (landscape) return;
        LayoutParams params = (LayoutParams) video.getLayoutParams();
        int top = topBox.getHeight(), bottom = bottomBox.getHeight();
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
            cardBox.setPadding(theme.px(8), pad, theme.px(8), pad);
            buttons.setPadding(0, 0, 0, 0);
            volumeRow.setPadding(theme.px(4), 0, theme.px(4), theme.px(16));
        } else {
            topBox.setPadding(pad, pad, pad, 0);
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
        // Portrait keeps the slider's room, so the picture does not jump when the speaker turns on.
        volumeRow.setVisibility(speakerOn ? VISIBLE : landscape ? GONE : INVISIBLE);
        // Every position keeps one job: left (top) answers, then is the microphone; middle is the speaker; right
        // (bottom) hangs up.
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
        column.addView(caption, captionParams);
        column.setOnClickListener(v -> safely(action));
        circle.setOnClickListener(v -> safely(action));
        LinearLayout.LayoutParams place = landscape
            ? new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            : new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        if (landscape) place.topMargin = theme.px(4);
        buttons.addView(column, place);
    }

    // Cards

    /** The cards from the settings; a change of cards rearranges, since landscape gives them a column only if any. */
    void setCards(List<Cards.Card> cards) {
        boolean column = this.cards.isEmpty() != cards.isEmpty();
        this.cards = cards;
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
        cardBox.setVisibility(cards.isEmpty() ? GONE : VISIBLE);
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
            CardHolder holder = new CardHolder(getContext(), cards.get(i));
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

    /** One card, like Home Assistant's tile: a tinted icon circle, the label and the state. */
    private final class CardHolder {
        final Cards.Card card;
        final HoldFrame root;
        final ImageView icon;
        final GradientDrawable circle = new GradientDrawable();
        final TextView label, state;
        private String feedback;
        private final Runnable clearFeedback = () -> feedback(null);

        CardHolder(Context context, Cards.Card card) {
            this.card = card;
            root = new HoldFrame(context);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(theme.px(12), 0, theme.px(12), 0);
            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(theme.px(16));
            background.setColor(0x26FFFFFF);
            root.setBackground(background);
            circle.setShape(GradientDrawable.OVAL);
            icon = new ImageView(context);
            icon.setBackground(circle);
            icon.setScaleType(ImageView.ScaleType.CENTER);
            root.addView(icon, new LinearLayout.LayoutParams(theme.px(40), theme.px(40)));
            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            label = new TextView(context);
            theme.styleText(label, 15, 600, "onSurface");
            label.setTextColor(Color.WHITE);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            state = new TextView(context);
            theme.styleText(state, 13, 400, "onSurfaceVariant");
            state.setTextColor(0xFFBDBDBD);
            state.setSingleLine(true);
            state.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(label);
            texts.addView(state);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            textParams.leftMargin = theme.px(12);
            root.addView(texts, textParams);
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
            label.setText(look.label);
            String shown = feedback != null ? feedback : look.state;
            state.setText(shown);
            state.setVisibility(shown.isEmpty() ? GONE : VISIBLE);
            int accent = tintColor(look.tint);
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
        }

        void cancel() {
            root.removeCallbacks(clearFeedback);
            root.cancelHold();
        }
    }

    /** A card that runs only when held: a fill sweeps across it, and lifting the finger early cancels. */
    private final class HoldFrame extends LinearLayout {
        Runnable hold, tooShort;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();
        private ValueAnimator animator;
        private float progress;

        HoldFrame(Context context) {
            super(context);
            fill.setColor(0x33FFFFFF);
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
