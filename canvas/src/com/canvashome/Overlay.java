package com.canvashome;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * The two app icons over Hisense's Art Mode. An accessibility service, because that is the one
 * kind of sideloaded app that can see which app is on screen, draw over it
 * (TYPE_ACCESSIBILITY_OVERLAY, no extra permission) and see remote presses before Art Mode does.
 *
 * The icons show in the lower right whenever Art Mode is on screen showing a picture, and hide while
 * one of its menus or galleries is open (they use Down themselves). Art Mode keeps the
 * remote: only the focus key (Down by default) is taken, and moves focus onto the icons; then
 * Left/Right choose, OK opens, and Back, Up or a few idle seconds give the remote back. Every other
 * key goes to Art Mode as usual.
 *
 * It is also what lets {@link Schedule} start activities from the background (Android lets an app
 * with an accessibility service on do that).
 */
public class Overlay extends AccessibilityService {
    // Sizes in dp at the TV's UI density, colors ARGB.
    private static final int ICON_DP = 40;
    private static final int GAP_DP = 26;
    private static final int PAD_DP = 14;
    private static final int RIGHT_DP = 56;
    private static final int BOTTOM_DP = 40;
    // Fainter than Frame's home screen (0.42): over a painting they should barely be there.
    private static final int PILL = 0x33000000;
    private static final int ICON = 0xFFF2F2F0;
    private static final float IDLE_ALPHA = 0.26f;
    private static final float DIM_ALPHA = 0.20f;
    private static final float SELECTED_ALPHA = 0.9f;
    private static final long FOCUS_TIMEOUT_MS = 8000;
    // Art Mode showing a picture has nothing focusable but this; its menus and galleries do.
    private static final String PICTURE_VIEW = ":id/hi_video_surface_view";
    // Art Mode's menus close on their own without an event, so while one is open, look again this often.
    private static final long MENU_CHECK_MS = 1000;

    private static volatile Overlay instance;

    /** The running service, or null if its switch is off. */
    static Overlay get() {
        return instance;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private LinearLayout bar;
    private ImageView[] icons;
    private String[] labels;
    private String[][] packages;
    private volatile boolean shown;
    private volatile boolean artOn;
    private boolean focused;
    private int selected;

    private final Runnable recheck = new Runnable() {
        @Override
        public void run() {
            update();
        }
    };

    private final Runnable unfocus = new Runnable() {
        @Override
        public void run() {
            setFocused(false);
        }
    };

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            update();
            Schedule.screenChanged(c, Intent.ACTION_SCREEN_ON.equals(intent.getAction()));
        }
    };

    @Override
    protected void onServiceConnected() {
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        labels = getResources().getStringArray(R.array.app_labels);
        String[] pk = getResources().getStringArray(R.array.app_packages);
        String[] glyphs = getResources().getStringArray(R.array.app_icons);
        packages = new String[pk.length][];
        for (int i = 0; i < pk.length; i++) packages[i] = pk[i].split("\\|");
        buildBar(glyphs);
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screen, f);
        Schedule.arm(this);
        update();
        Log.i(Config.TAG, "overlay service on; Art Mode is " + (Config.artPackage(this).length() > 0
                ? Config.artPackage(this) : "not learned yet (./install.sh learn)"));
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        try {
            unregisterReceiver(screen);
        } catch (IllegalArgumentException ignored) {
            // never registered
        }
        main.removeCallbacks(recheck);
        show(false);
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int t = event.getEventType();
        if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || t == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            update();
        } else if (shown != (artOn && !browsing())) {
            // A menu opened or closed inside Art Mode.
            update();
        }
    }

    @Override
    public void onInterrupt() {
    }

    /** True while Art Mode is the app on screen and the screen is on, menus open or not. */
    boolean artShowing() {
        return artOn;
    }

    /** Shows or hides the icons for whatever is on screen now. */
    void update() {
        main.post(new Runnable() {
            @Override
            public void run() {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                String art = Config.artPackage(Overlay.this);
                String top = topPackage();
                artOn = pm.isInteractive() && art.length() > 0 && art.equals(top);
                boolean menu = artOn && browsing();
                show(artOn && !menu);
                main.removeCallbacks(recheck);
                if (menu) main.postDelayed(recheck, MENU_CHECK_MS);
            }
        });
    }

    /**
     * True while one of Art Mode's menus or galleries is open: something other than the picture
     * itself can take focus.
     */
    private boolean browsing() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        List<AccessibilityNodeInfo> todo = new ArrayList<AccessibilityNodeInfo>();
        todo.add(root);
        boolean found = false;
        while (!todo.isEmpty()) {
            AccessibilityNodeInfo n = todo.remove(todo.size() - 1);
            if (!found && n.isFocusable() && n.isVisibleToUser()) {
                String id = n.getViewIdResourceName();
                found = id == null || !id.endsWith(PICTURE_VIEW);
            }
            if (!found) {
                for (int i = 0; i < n.getChildCount(); i++) {
                    AccessibilityNodeInfo child = n.getChild(i);
                    if (child != null) todo.add(child);
                }
            }
            n.recycle();
        }
        for (AccessibilityNodeInfo n : todo) n.recycle();
        return found;
    }

    /** The package of the focused app window. Our own overlay isn't an app window, so it never counts. */
    String topPackage() {
        List<AccessibilityWindowInfo> windows = getWindows();
        String fallback = null;
        for (AccessibilityWindowInfo w : windows) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null) continue;
            String p = root.getPackageName() == null ? null : root.getPackageName().toString();
            root.recycle();
            if (w.isFocused() || w.isActive()) return p;
            if (fallback == null) fallback = p;
        }
        return fallback;
    }

    // ---------------------------------------------------------------- the icons

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void buildBar(String[] glyphs) {
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(PILL);
        pill.setCornerRadius(dp(ICON_DP / 2f + PAD_DP));
        bar.setBackground(pill);
        bar.setPadding(dp(PAD_DP + 4), dp(PAD_DP), dp(PAD_DP + 4), dp(PAD_DP));
        icons = new ImageView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            ImageView v = new ImageView(this);
            AppGlyph g = AppGlyph.forName(i < glyphs.length ? glyphs[i] : "", ICON);
            if (g != null) v.setImageDrawable(g);
            v.setContentDescription(labels[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP));
            if (i > 0) lp.leftMargin = dp(GAP_DP);
            bar.addView(v, lp);
            icons[i] = v;
        }
        paint();
    }

    private void show(boolean on) {
        if (on == shown) return;
        shown = on;
        if (on) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM | Gravity.END;
            lp.x = dp(RIGHT_DP);
            lp.y = dp(BOTTOM_DP);
            try {
                wm.addView(bar, lp);
            } catch (RuntimeException e) {
                shown = false;
                Log.w(Config.TAG, "could not draw over Art Mode: " + e);
            }
        } else {
            setFocused(false);
            if (bar.getParent() != null) wm.removeViewImmediate(bar);
        }
    }

    private void setFocused(boolean on) {
        focused = on;
        main.removeCallbacks(unfocus);
        if (on) main.postDelayed(unfocus, FOCUS_TIMEOUT_MS);
        paint();
    }

    private void paint() {
        for (int i = 0; i < icons.length; i++) {
            boolean sel = focused && i == selected;
            icons[i].setAlpha(!focused ? IDLE_ALPHA : sel ? SELECTED_ALPHA : DIM_ALPHA);
            icons[i].setScaleX(sel ? 1.18f : 1f);
            icons[i].setScaleY(sel ? 1.18f : 1f);
        }
    }

    // ---------------------------------------------------------------- the remote

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (!shown) return false;
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (!focused) {
            if (code != Config.focusKey(this)) return false;
            if (browsing()) {
                // A menu opened since the last update: the key is Art Mode's.
                update();
                return false;
            }
            if (down && e.getRepeatCount() == 0) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        setFocused(true);
                    }
                });
            }
            return true;
        }
        final int move;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                move = -1;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                move = 1;
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                // On release, so the key-up doesn't land in the app that opens.
                if (!down) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            open(selected);
                        }
                    });
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_DPAD_UP:
                if (!down) {
                    main.post(unfocus);
                }
                return true;
            default:
                if (code == Config.focusKey(this)) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            setFocused(true);
                        }
                    });
                    return true;
                }
                // Anything else gives the remote back to Art Mode, with this key.
                main.post(unfocus);
                return false;
        }
        if (down) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    selected = Math.max(0, Math.min(icons.length - 1, selected + move));
                    setFocused(true);
                }
            });
        }
        return true;
    }

    private void open(int i) {
        setFocused(false);
        PackageManager pm = getPackageManager();
        Intent launch = null;
        for (String p : packages[i]) {
            launch = pm.getLeanbackLaunchIntentForPackage(p.trim());
            if (launch == null) launch = pm.getLaunchIntentForPackage(p.trim());
            if (launch != null) break;
        }
        if (launch == null) {
            Toast.makeText(this, labels[i] + " isn't installed.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, labels[i] + " couldn't be opened.", Toast.LENGTH_LONG).show();
        }
    }

    /** Turns the screen off, as the remote's power button would; see {@link Schedule#sleep}. */
    boolean lockScreen() {
        return performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }

    /** For the status line. */
    static String describe(Context c) {
        Overlay o = instance;
        if (o == null) return "Overlay service: off (./install.sh turns it on).";
        return "Overlay service: on, " + (o.shown ? "icons showing over Art Mode." : "on screen now: " + o.topPackage() + ".");
    }
}
