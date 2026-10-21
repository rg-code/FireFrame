package com.framehome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen: the apps from res/values/apps.xml as large icons or text in the lower left, over one of
 * the screensaver's saved photos or paintings, dimmed (a painting's title, artist and caption are
 * in the lower right). The time and the weather are in the upper right. Up/Down moves, OK opens. The Menu key shows a second
 * row with Fire TV settings, a screensaver preview, and the screensaver's sync status.
 */
public class HomeActivity extends Activity {
    private static final int PAPER = 0xFFF2F2F0;
    private static final float DIM = 0.42f;

    private static final class Slot {
        final String label;
        final String[] packages;
        TextView view;
        Intent launch;

        Slot(String label, String packages) {
            this.label = label;
            this.packages = packages.split("\\|");
        }
    }

    private final List<Slot> slots = new ArrayList<Slot>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService io;
    private PhotoStore store;
    private ArtStore art;
    private final Random random = new Random();

    private ImageView wallpaper;
    private LinearLayout menu;
    private TextView firstMenuItem;
    private TextView status;
    private TextView clock;
    private TextView weather;
    private TextView credit;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            clock.setText(DateFormat.getTimeFormat(HomeActivity.this).format(new Date()));
            refreshWeather();
            long now = System.currentTimeMillis();
            main.postDelayed(this, 60_000 - now % 60_000 + 50);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = PhotoStore.get(this);
        art = ArtStore.get(this);
        io = Executors.newSingleThreadExecutor();
        HealthJob.schedule(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        wallpaper = new ImageView(this);
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaper.setAlpha(0f);
        root.addView(wallpaper, match());

        // Darkest behind the text in the lower left, nearly clear in the upper right.
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BL_TR,
                new int[] {0xE6000000, 0x99000000, 0x40000000}));
        root.addView(scrim, match());

        LinearLayout time = new LinearLayout(this);
        time.setOrientation(LinearLayout.VERTICAL);
        time.setGravity(Gravity.END);
        clock = text(20, "sans-serif-light", Typeface.NORMAL);
        clock.setAlpha(0.6f);
        time.addView(clock);
        weather = text(14, "sans-serif-light", Typeface.NORMAL);
        weather.setAlpha(0.6f);
        weather.setGravity(Gravity.CENTER_VERTICAL);
        weather.setPadding(0, dp(6), 0, 0);
        time.addView(weather);
        root.addView(time, corner(Gravity.TOP | Gravity.END));

        credit = text(12, "sans-serif", Typeface.NORMAL);
        credit.setAlpha(0.5f);
        credit.setMaxWidth(dp(420));
        credit.setLineSpacing(0, 1.2f);
        credit.setGravity(Gravity.END);
        root.addView(credit, corner(Gravity.BOTTOM | Gravity.END));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, corner(Gravity.BOTTOM | Gravity.START));

        String[] labels = getResources().getStringArray(R.array.app_labels);
        String[] packages = getResources().getStringArray(R.array.app_packages);
        String[] icons = getResources().getStringArray(R.array.app_icons);
        for (int i = 0; i < Math.min(labels.length, packages.length); i++) {
            final Slot slot = new Slot(labels[i], packages[i]);
            AppGlyph glyph = i < icons.length ? AppGlyph.forName(icons[i].trim(), PAPER) : null;
            if (glyph == null) {
                slot.view = item(slot.label, 44, "sans-serif-light");
            } else {
                // The icon takes the text's place; it dims and brightens with focus the same way.
                slot.view = item("", 44, "sans-serif-light");
                slot.view.setContentDescription(slot.label);
                glyph.setBounds(0, 0, dp(60), dp(60));
                slot.view.setCompoundDrawables(glyph, null, null, null);
                slot.view.setPadding(0, dp(8), dp(12), dp(8));
            }
            slot.view.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    open(slot);
                }
            });
            column.addView(slot.view, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            slots.add(slot);
        }

        menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setVisibility(View.GONE);
        menu.setPadding(0, dp(22), 0, 0);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        firstMenuItem = item("Fire TV settings", 18, "sans-serif");
        firstMenuItem.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSettings();
            }
        });
        TextView preview = item("Preview screensaver", 18, "sans-serif");
        preview.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(HomeActivity.this, SlideshowActivity.class));
            }
        });
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gap.rightMargin = dp(36);
        row.addView(firstMenuItem, gap);
        row.addView(preview);
        menu.addView(row);
        status = text(13, "sans-serif", Typeface.NORMAL);
        status.setAlpha(0.55f);
        status.setLineSpacing(0, 1.25f);
        status.setPadding(0, dp(10), 0, 0);
        status.setMaxWidth(dp(560));
        menu.addView(status);
        column.addView(menu);

        setContentView(root);
        if (!slots.isEmpty()) slots.get(0).view.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resolveApps();
        loadWallpaper();
        main.removeCallbacks(tick);
        main.post(tick);
        if (menu.getVisibility() == View.VISIBLE) refreshStatus();
        io.execute(new Runnable() {
            @Override
            public void run() {
                ScreensaverRouter.apply(HomeActivity.this, ScreensaverRouter.decide(HomeActivity.this, null));
            }
        });
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            toggleMenu();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) return true;
        return super.onKeyDown(keyCode, event);
    }

    /** This is the home screen: Back closes the menu, and otherwise has nowhere to go. */
    @Override
    public void onBackPressed() {
        if (menu.getVisibility() == View.VISIBLE) toggleMenu();
    }

    private void toggleMenu() {
        if (menu.getVisibility() == View.VISIBLE) {
            menu.setVisibility(View.GONE);
            if (!slots.isEmpty()) slots.get(0).view.requestFocus();
        } else {
            status.setText("");
            menu.setVisibility(View.VISIBLE);
            firstMenuItem.requestFocus();
            refreshStatus();
        }
    }

    private void refreshStatus() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                final String s = store.status() + "\n" + art.status() + "\n" + ScreensaverRouter.lastRoute(HomeActivity.this);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        status.setText(s);
                    }
                });
            }
        });
    }

    private void resolveApps() {
        PackageManager pm = getPackageManager();
        for (Slot slot : slots) {
            slot.launch = null;
            for (String pkg : slot.packages) {
                Intent i = pm.getLeanbackLaunchIntentForPackage(pkg.trim());
                if (i == null) i = pm.getLaunchIntentForPackage(pkg.trim());
                if (i != null) {
                    slot.launch = i;
                    break;
                }
            }
        }
    }

    private void open(Slot slot) {
        if (slot.launch == null) {
            Toast.makeText(this, slot.label + " isn't installed. Install it with Downloader, then come back here.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        try {
            startActivity(new Intent(slot.launch).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, slot.label + " couldn't be opened.", Toast.LENGTH_LONG).show();
        }
    }

    private void openSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        } catch (ActivityNotFoundException ignored) {
            // fall through to Amazon's settings app
        }
        Intent amazon = getPackageManager().getLaunchIntentForPackage("com.amazon.tv.settings.v2");
        if (amazon != null) startActivity(amazon.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        else Toast.makeText(this, "Hold Home on the remote to open Settings.", Toast.LENGTH_LONG).show();
    }

    private void refreshWeather() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                Weather.refreshIfStale(HomeActivity.this);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        Weather.show(HomeActivity.this, weather);
                    }
                });
            }
        });
    }

    /** A saved photo or painting (half and half when there are both), else an imported background. */
    private void loadWallpaper() {
        final int w = 1920;
        final int h = 1080;
        io.execute(new Runnable() {
            @Override
            public void run() {
                PhotoStore.Shown shown = null;
                if (art.savedCount() > 0 && (store.savedCount() == 0 || random.nextBoolean())) shown = art.next(w, h);
                final Bitmap b = shown != null ? shown.bitmap : store.randomSaved(w, h);
                final String by = shown == null ? "" : shown.title + (shown.artist.length() > 0 ? "\n" + shown.artist : "")
                        + (shown.caption.length() > 0 ? "\n" + shown.caption : "");
                // Paintings sit whole on the right, clear of the menu; photos fill the screen.
                final boolean whole = shown != null && b != null && b.getWidth() * 9 < b.getHeight() * 16;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isDestroyed()) {
                            if (b != null) b.recycle();
                            return;
                        }
                        Drawable old = wallpaper.getDrawable();
                        credit.setText(by);
                        wallpaper.setScaleType(whole ? ImageView.ScaleType.FIT_END : ImageView.ScaleType.CENTER_CROP);
                        if (b == null) {
                            wallpaper.setAlpha(0f);
                        } else {
                            wallpaper.setImageBitmap(b);
                            wallpaper.setAlpha(0f);
                            wallpaper.animate().alpha(0.7f).setDuration(400).start();
                        }
                        if (old instanceof BitmapDrawable && b != null) {
                            Bitmap ob = ((BitmapDrawable) old).getBitmap();
                            if (ob != null && ob != b && !ob.isRecycled()) ob.recycle();
                        }
                    }
                });
            }
        });
    }

    /** Focusable text: full white when focused, dimmed otherwise. No boxes, no underlines. */
    private TextView item(String label, int sp, String family) {
        final TextView t = text(sp, family, Typeface.NORMAL);
        t.setText(label);
        t.setFocusable(true);
        t.setClickable(true);
        t.setAlpha(DIM);
        t.setPadding(0, dp(4), dp(12), dp(4));
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean focused) {
                v.animate().alpha(focused ? 1f : DIM).translationX(focused ? dp(6) : 0f).setDuration(140).start();
            }
        });
        return t;
    }

    private TextView text(int sp, String family, int style) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(family, style));
        t.setTextColor(PAPER);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Fire TV's title-safe area: 5% in from each edge. */
    private FrameLayout.LayoutParams corner(int gravity) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        p.gravity = gravity;
        p.leftMargin = p.rightMargin = dp(56);
        p.topMargin = dp(32);
        p.bottomMargin = dp(44);
        return p;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
