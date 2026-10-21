package com.framehome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Date;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen photos from {@link PhotoStore}, alternating with paintings from {@link ArtStore} when
 * both are available. Each one crossfades in. Photos drift slowly (landscape ones fill the screen,
 * portrait ones sit centred on black) with the place and month in the lower left; paintings are
 * shown whole and still, with their title, artist and one sentence about them. The time and the
 * weather are in the lower right. The next picture is fetched while the current one is on screen.
 * Used by the screensaver and by its preview.
 */
final class Slideshow {
    /** Lets the screensaver react when there is nothing to show (see {@link ImmichDream}). */
    interface Host {
        /** Called once, if the first photo can't be shown. Return true if the host takes over. */
        boolean onNothingToShow(String why);
    }

    private static volatile int runningCount;

    static boolean isRunning() {
        return runningCount > 0;
    }

    private static final long FADE_MS = 1600;
    private static final long RETRY_MS = 60_000;
    private static final int PAPER = 0xFFF2F2F0;

    private final Context context;
    private final PhotoStore store;
    private final ArtStore art;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private final FrameLayout root;
    private final FrameLayout stage;
    private final ImageView[] layers = new ImageView[2];
    private final Animator[] drifts = new Animator[2];
    private final TextView caption;
    private final LinearLayout card;
    private final TextView cardTitle;
    private final TextView cardArtist;
    private final TextView cardCaption;
    private final TextView clock;
    private final TextView weather;
    private final TextView message;

    private final Host host;
    private ExecutorService io;
    private boolean running;
    private boolean shownAny;
    private boolean askedHost;
    private int front;
    private long nextDue;
    private boolean preferArt = true;

    private final Runnable fetch = new Runnable() {
        @Override
        public void run() {
            fetchNext();
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            clock.setText(DateFormat.getTimeFormat(context).format(new Date()));
            refreshWeather();
            long now = System.currentTimeMillis();
            main.postDelayed(this, 60_000 - now % 60_000 + 50);
        }
    };

    Slideshow(Context context) {
        this(context, null);
    }

    Slideshow(Context context, Host host) {
        this.context = context;
        this.host = host;
        this.store = PhotoStore.get(context);
        this.art = ArtStore.get(context);

        root = new FrameLayout(context);
        root.setBackgroundColor(Color.BLACK);

        stage = new FrameLayout(context);
        root.addView(stage, match());
        for (int i = 0; i < 2; i++) {
            ImageView v = new ImageView(context);
            v.setAlpha(0f);
            layers[i] = v;
            stage.addView(v, match());
        }

        // A low gradient so the caption and clock stay legible on bright photos.
        View scrim = new View(context);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] {0x99000000, 0x00000000}));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(160));
        sp.gravity = Gravity.BOTTOM;
        root.addView(scrim, sp);

        caption = text(15, Typeface.create("serif", Typeface.ITALIC), 0.8f);
        root.addView(caption, corner(Gravity.BOTTOM | Gravity.START));

        card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setVisibility(View.GONE);
        cardTitle = text(20, Typeface.create("serif", Typeface.NORMAL), 0.95f);
        cardArtist = text(13, Typeface.create("sans-serif", Typeface.NORMAL), 0.75f);
        cardArtist.setPadding(0, dp(4), 0, 0);
        cardCaption = text(13, Typeface.create("sans-serif-light", Typeface.NORMAL), 0.7f);
        cardCaption.setPadding(0, dp(6), 0, 0);
        cardCaption.setMaxLines(2);
        cardCaption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        for (TextView t : new TextView[] {cardTitle, cardArtist, cardCaption}) {
            t.setMaxWidth(dp(560));
            card.addView(t);
        }
        root.addView(card, corner(Gravity.BOTTOM | Gravity.START));

        // The time, with the weather just below it in a smaller size.
        LinearLayout time = new LinearLayout(context);
        time.setOrientation(LinearLayout.VERTICAL);
        time.setGravity(Gravity.END);
        clock = text(22, Typeface.create("sans-serif-light", Typeface.NORMAL), 0.8f);
        time.addView(clock);
        weather = text(15, Typeface.create("sans-serif-light", Typeface.NORMAL), 0.8f);
        weather.setGravity(Gravity.CENTER_VERTICAL);
        time.addView(weather);
        root.addView(time, corner(Gravity.BOTTOM | Gravity.END));

        message = text(18, Typeface.create("sans-serif", Typeface.NORMAL), 0.75f);
        message.setLineSpacing(0, 1.3f);
        message.setVisibility(View.GONE);
        FrameLayout.LayoutParams mp = corner(Gravity.CENTER_VERTICAL | Gravity.START);
        mp.rightMargin = dp(240);
        root.addView(message, mp);
    }

    View view() {
        return root;
    }

    void start() {
        if (running) return;
        running = true;
        runningCount++;
        io = Executors.newSingleThreadExecutor();
        main.post(tick);
        io.execute(new Runnable() {
            @Override
            public void run() {
                store.syncIfStale();
            }
        });
        nextDue = SystemClock.uptimeMillis();
        fetchNext();
    }

    void stop() {
        if (!running) return;
        running = false;
        runningCount--;
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
        for (int i = 0; i < 2; i++) {
            if (drifts[i] != null) drifts[i].cancel();
            layers[i].animate().cancel();
            release(layers[i]);
        }
    }

    private void fetchNext() {
        final int w = root.getWidth() > 0 ? root.getWidth() : 1920;
        final int h = root.getHeight() > 0 ? root.getHeight() : 1080;
        final ExecutorService exec = io;
        if (exec == null || exec.isShutdown()) return;
        exec.execute(new Runnable() {
            @Override
            public void run() {
                final PhotoStore.Shown shown = pick(w, h);
                final String empty = shown == null ? store.emptyMessage() : null;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!running) {
                            if (shown != null) shown.bitmap.recycle();
                            return;
                        }
                        long wait = Math.max(0, nextDue - SystemClock.uptimeMillis());
                        main.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (!running) {
                                    if (shown != null) shown.bitmap.recycle();
                                    return;
                                }
                                if (shown == null) showEmpty(empty);
                                else show(shown);
                            }
                        }, wait);
                    }
                });
            }
        });
    }

    /** Alternates between photos and art while both can be shown; otherwise whichever can. */
    private PhotoStore.Shown pick(int w, int h) {
        PhotoStore.Shown s = null;
        boolean tryArt = preferArt;
        if (tryArt) s = art.next(w, h);
        if (s == null) s = store.next(w, h);
        if (s == null && !tryArt) s = art.next(w, h);
        preferArt = !preferArt;
        return s;
    }

    private void refreshWeather() {
        final ExecutorService exec = io;
        if (exec == null || exec.isShutdown()) return;
        exec.execute(new Runnable() {
            @Override
            public void run() {
                Weather.refreshIfStale(context);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        Weather.show(context, weather);
                    }
                });
            }
        });
    }

    private void showEmpty(String text) {
        if (host != null && !shownAny && !askedHost) {
            askedHost = true;
            if (host.onNothingToShow(text)) return;
        }
        message.setText(text);
        message.setVisibility(View.VISIBLE);
        // Nudge the text a little on every retry so a long outage doesn't leave a static image.
        message.setTranslationX(dp(random.nextInt(40)));
        message.setTranslationY(dp(random.nextInt(60) - 30));
        caption.setText("");
        card.setVisibility(View.GONE);
        nextDue = SystemClock.uptimeMillis() + RETRY_MS;
        main.postDelayed(fetch, RETRY_MS);
    }

    private void show(PhotoStore.Shown shown) {
        shownAny = true;
        message.setVisibility(View.GONE);
        final int back = 1 - front;
        final ImageView in = layers[back];
        final ImageView out = layers[front];
        long interval = store.intervalSeconds() * 1000L;

        if (drifts[back] != null) drifts[back].cancel();
        in.animate().cancel();
        release(in);
        // Paintings are shown whole inside the title-safe area, like a print on a black mat.
        boolean portrait = shown.art || shown.bitmap.getHeight() > shown.bitmap.getWidth();
        in.setScaleType(portrait ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
        if (shown.art) in.setPadding(dp(48), dp(27), dp(48), dp(27));
        else in.setPadding(0, 0, 0, 0);
        in.setImageBitmap(shown.bitmap);
        in.setScaleX(1f);
        in.setScaleY(1f);
        in.setTranslationX(0f);
        in.setTranslationY(0f);
        in.setAlpha(0f);
        in.bringToFront();
        in.animate().setListener(null).alpha(1f).setDuration(FADE_MS).start();

        if (!portrait && motionAllowed()) {
            float dx = (random.nextFloat() - 0.5f) * 0.05f * root.getWidth();
            float dy = (random.nextFloat() - 0.5f) * 0.05f * root.getHeight();
            AnimatorSet drift = new AnimatorSet();
            drift.playTogether(
                    ObjectAnimator.ofFloat(in, View.SCALE_X, 1f, 1.08f),
                    ObjectAnimator.ofFloat(in, View.SCALE_Y, 1f, 1.08f),
                    ObjectAnimator.ofFloat(in, View.TRANSLATION_X, 0f, dx),
                    ObjectAnimator.ofFloat(in, View.TRANSLATION_Y, 0f, dy));
            drift.setDuration(interval + 2 * FADE_MS);
            drift.setInterpolator(new LinearInterpolator());
            drift.start();
            drifts[back] = drift;
        }

        out.animate().cancel();
        out.animate().alpha(0f).setDuration(FADE_MS).setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                out.animate().setListener(null);
                if (drifts[1 - back] != null) drifts[1 - back].cancel();
                if (running && layers[front] != out) release(out);
            }
        }).start();

        if (shown.art) {
            caption.setText("");
            cardTitle.setText(shown.title);
            cardArtist.setText(shown.artist);
            cardCaption.setText(shown.caption);
            cardCaption.setVisibility(shown.caption.length() > 0 ? View.VISIBLE : View.GONE);
            fitCard(shown.bitmap.getWidth(), shown.bitmap.getHeight());
            card.setVisibility(View.VISIBLE);
            card.setAlpha(0f);
            card.animate().alpha(1f).setDuration(FADE_MS).start();
        } else {
            card.setVisibility(View.GONE);
            caption.setText(shown.caption);
            caption.setAlpha(0f);
            caption.animate().alpha(0.8f).setDuration(FADE_MS).start();
        }

        front = back;
        nextDue = SystemClock.uptimeMillis() + interval;
        fetchNext();
    }

    /**
     * Narrow paintings leave black space at the sides: the card goes there, as wide as that space,
     * so it doesn't cover the painting. Wide ones leave none, and the card sits over the lower left.
     */
    private void fitCard(int bw, int bh) {
        int availW = root.getWidth() - dp(96);
        int availH = root.getHeight() - dp(54);
        if (availW <= 0 || availH <= 0) return;
        int shownW = Math.min(availW, Math.round((float) availH * bw / bh));
        int side = (root.getWidth() - shownW) / 2 - dp(48) - dp(24);
        int max = side >= dp(220) ? side : dp(560);
        for (TextView t : new TextView[] {cardTitle, cardArtist, cardCaption}) t.setMaxWidth(max);
        cardCaption.setMaxLines(side >= dp(220) ? 4 : 2);
    }

    private boolean motionAllowed() {
        return Settings.Global.getFloat(context.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
    }

    private static void release(ImageView v) {
        Drawable d = v.getDrawable();
        v.setImageDrawable(null);
        if (d instanceof BitmapDrawable) {
            Bitmap b = ((BitmapDrawable) d).getBitmap();
            if (b != null && !b.isRecycled()) b.recycle();
        }
    }

    private TextView text(int sp, Typeface face, float alpha) {
        TextView t = new TextView(context);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(face);
        t.setTextColor(PAPER);
        t.setAlpha(alpha);
        t.setShadowLayer(dp(6), 0, dp(1), 0xAA000000);
        return t;
    }

    /** Fire TV's title-safe area is 5% in from each edge: 48 x 27 dp on a 960 x 540 dp screen. */
    private FrameLayout.LayoutParams corner(int gravity) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        p.gravity = gravity;
        p.leftMargin = p.rightMargin = dp(48);
        p.topMargin = p.bottomMargin = dp(30);
        return p;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private int dp(float v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }
}
