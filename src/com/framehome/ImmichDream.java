package com.framehome;

import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.util.Log;

/**
 * The system screensaver. Any key wakes the TV, as with Amazon's own screensaver.
 *
 * If the first photo can't be shown (Immich not set up, or offline with nothing saved), it follows
 * the fallback from {@link ScreensaverRouter}: hands over to Aerial Views on the spot, or ends so
 * the stick sleeps, and switches the system setting so the next idle goes straight to the fallback.
 */
public class ImmichDream extends DreamService implements Slideshow.Host {
    private final Handler main = new Handler(Looper.getMainLooper());
    private Slideshow slideshow;
    private PhotoStore store;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        store = PhotoStore.get(this);
        slideshow = new Slideshow(this, this);
        setContentView(slideshow.view());
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        store.dreamStarted();
        slideshow.start();
    }

    @Override
    public void onDreamingStopped() {
        slideshow.stop();
        store.dreamStopped();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        main.removeCallbacksAndMessages(null);
        if (slideshow != null) slideshow.stop();
        super.onDetachedFromWindow();
    }

    @Override
    public boolean onNothingToShow(final String why) {
        String mode = ScreensaverRouter.mode(this);
        if ("none".equals(mode)) return false;
        store.dreamFailed(why.replace('\n', ' '));
        final String fallback = ScreensaverRouter.fallbackComponent(this);
        // Switching the setting off the main thread: it's a few Binder calls.
        new Thread("frame-route") {
            @Override
            public void run() {
                Log.i(PhotoStore.TAG, ScreensaverRouter.apply(ImmichDream.this,
                        ScreensaverRouter.decide(ImmichDream.this, null)));
            }
        }.start();
        if (fallback != null && fallback.startsWith(ScreensaverRouter.AERIAL_PKG + "/")
                && ScreensaverRouter.startAerial(this)) {
            // Aerial's activity starts behind this dream. Ending the dream may turn the screen off for
            // a moment (the idle timeout has passed); Aerial's activity switches it back on.
            Log.i(PhotoStore.TAG, "nothing to show, handed over to Aerial Views");
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    finish();
                }
            }, 1500);
        } else {
            // "sleep", Amazon's screensaver, or another dream we can't start directly: end now, the
            // stick sleeps, and the next idle runs the fallback.
            Log.i(PhotoStore.TAG, "nothing to show, ending the screensaver (fallback " + mode + ")");
            finish();
        }
        return true;
    }
}
