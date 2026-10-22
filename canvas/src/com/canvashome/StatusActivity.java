package com.canvashome;

import android.app.Activity;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

/**
 * What Canvas is doing, as text: the one screen it has, in the TV's app list. Opening it once after
 * installing also lets Android deliver the boot and alarm broadcasts.
 */
public class StatusActivity extends Activity {
    private TextView text;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        text = new TextView(this);
        text.setTextColor(0xFFF2F2F0);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        text.setLineSpacing(0, 1.3f);
        text.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(getResources().getDisplayMetrics().density * 64);
        text.setPadding(pad, pad, pad, pad);
        setContentView(text);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ArtJob.schedule(this);
        Schedule.arm(this);
        new Thread("canvas-status") {
            @Override
            public void run() {
                final String s = Status.describe(StatusActivity.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        text.setText("Canvas\n\n" + s);
                    }
                });
            }
        }.start();
    }
}
