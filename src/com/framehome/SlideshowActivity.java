package com.framehome;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;

/** The screensaver in an activity, to try it without waiting. Any key closes it. */
public class SlideshowActivity extends Activity {
    private Slideshow slideshow;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        slideshow = new Slideshow(this);
        setContentView(slideshow.view());
    }

    @Override
    protected void onResume() {
        super.onResume();
        slideshow.start();
    }

    @Override
    protected void onPause() {
        slideshow.stop();
        super.onPause();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        finish();
        return true;
    }
}
