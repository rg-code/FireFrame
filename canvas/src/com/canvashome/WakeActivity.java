package com.canvashome;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.view.WindowManager;

/**
 * Turns the screen on and opens Art Mode, for {@link Schedule}. Art Mode's own activity can't be
 * asked to turn the screen on, so this one does, then hands over and closes.
 */
public class WakeActivity extends Activity {
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A moment for the panel to come on before Art Mode starts drawing.
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                Schedule.startArt(WakeActivity.this);
                finish();
                overridePendingTransition(0, 0);
            }
        }, 600);
    }
}
