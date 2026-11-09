package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextClock;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Photo screensaver: a slideshow of the family's own photos, no account needed.
 * Looks for a folder named TVPhotos on any USB drive (/storage/XXXX-XXXX/TVPhotos), and in the TV's
 * own storage (/sdcard/TVPhotos, /sdcard/Pictures/TVPhotos). Photos cross-fade every SLIDE_MS with a
 * clock in the corner. Needs READ_EXTERNAL_STORAGE (granted over adb with pm grant).
 */
public class PhotoDream extends DreamService {

    static final String FOLDER = "TVPhotos";
    private static final long SLIDE_MS = 15_000L;
    private static final long FADE_MS = 1_500L;
    private static final long RESCAN_MS = 10 * 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<File> photos = new ArrayList<>();
    private ImageView front, back;
    private TextView message;
    private TextView namazLine;
    private int index = -1;
    private long lastScan;
    private int screenW = 1920, screenH = 1080;
    private boolean running;
    private android.widget.LinearLayout clockBox;
    private float density = 1f;
    /** Gravity of the corner the clock sits in; picked per photo where the picture is calmest and darkest. */
    private int clockCorner = Gravity.BOTTOM | Gravity.END;

    private final Runnable next = this::showNext;

    /** The next jamaat under the clock, refreshed every minute (NamazTimes, set in NamazActivity). */
    private final Runnable namazTick = new Runnable() {
        @Override
        public void run() {
            if (!running || namazLine == null) return;
            if (NamazTimes.showOnScreensaver(PhotoDream.this)) {
                namazLine.setText(NamazTimes.nextLine(PhotoDream.this));
                namazLine.setVisibility(View.VISIBLE);
            } else {
                namazLine.setVisibility(View.GONE);
            }
            long now = System.currentTimeMillis();
            handler.postDelayed(this, 60_000L - (now % 60_000L) + 200L);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenW = Math.max(dm.widthPixels, 1280);
        screenH = Math.max(dm.heightPixels, 720);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        back = newImage();
        front = newImage();
        root.addView(back);
        root.addView(front);

        float d = dm.density;
        density = d;
        clockBox = new android.widget.LinearLayout(this);
        clockBox.setOrientation(android.widget.LinearLayout.VERTICAL);
        clockBox.setGravity(Gravity.END);
        clockBox.setPadding((int) (40 * d), (int) (8 * d), (int) (40 * d), (int) (8 * d));

        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(0xF2FFFFFF);
        clock.setTextSize(96);
        clock.setTypeface(UiFont.get(this, R.string.font_light));
        clock.setIncludeFontPadding(false);
        clock.setLetterSpacing(0f);
        clock.setShadowLayer(28, 0, 3, 0xE6000000);
        clockBox.addView(clock);

        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setTextColor(0xD9FFFFFF);
                date.setLetterSpacing(0f);
        date.setTypeface(UiFont.get(this, R.string.font_regular));
        date.setTextSize(28);
        date.setShadowLayer(16, 0, 2, 0xE6000000);
        date.setPadding(0, (int) (2 * d), (int) (4 * d), 0);
        clockBox.addView(date);

        namazLine = new TextView(this);
        namazLine.setTextColor(0xD9FFFFFF);
        namazLine.setTextSize(26);
        namazLine.setLetterSpacing(0f);
        namazLine.setTypeface(UiFont.get(this, R.string.font_regular));
        namazLine.setGravity(Gravity.END);
        namazLine.setLineSpacing(0, 1.15f);
        namazLine.setShadowLayer(14, 0, 2, 0xE6000000);
        namazLine.setPadding(0, (int) (4 * d), (int) (4 * d), 0);
        clockBox.addView(namazLine);

        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        cp.setMargins((int) (16 * d), (int) (12 * d), (int) (16 * d), (int) (12 * d));
        root.addView(clockBox, cp);

        message = new TextView(this);
        message.setTextColor(0xDDFFFFFF);
        message.setTextSize(26);
        message.setTypeface(UiFont.get(this, R.string.font_regular));
        message.setGravity(Gravity.CENTER);
        message.setLineSpacing(0, 1.3f);
        message.setText("Koi photo nahi mili\n\nPen drive mein \"" + FOLDER + "\" naam ka folder banaiye,\n"
                + "usme family photos daaliye aur pen drive TV mein lagaiye.");
        message.setVisibility(View.GONE);
        root.addView(message, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    private ImageView newImage() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setAlpha(0f);
        v.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        return v;
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        running = true;
        namazTick.run();
        io.execute(() -> {
            List<File> found = scan();
            handler.post(() -> {
                photos.clear();
                photos.addAll(found);
                lastScan = System.currentTimeMillis();
                showNext();
            });
        });
    }

    @Override
    public void onDreamingStopped() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        io.shutdownNow();
        super.onDetachedFromWindow();
    }

    private void showNext() {
        if (!running) return;
        if (System.currentTimeMillis() - lastScan > RESCAN_MS) {
            lastScan = System.currentTimeMillis();
            io.execute(() -> {
                List<File> found = scan();
                handler.post(() -> {
                    if (!found.isEmpty() || photos.isEmpty()) {
                        photos.clear();
                        photos.addAll(found);
                        index = -1;
                    }
                });
            });
        }
        if (photos.isEmpty()) {
            message.setVisibility(View.VISIBLE);
            handler.postDelayed(this::rescanWhenEmpty, 30_000L);
            return;
        }
        message.setVisibility(View.GONE);
        index = (index + 1) % photos.size();
        if (index == 0 && photos.size() > 2) {
            File last = photos.get(photos.size() - 1);
            Collections.shuffle(photos);
            // a new round never starts with the photo that just ended the last one
            if (photos.get(0).equals(last)) Collections.swap(photos, 0, photos.size() - 1);
        }
        File f = photos.get(index);
        io.execute(() -> {
            Bitmap bmp = decode(f);
            int corner = bmp != null ? calmestCorner(bmp) : clockCorner;
            handler.post(() -> {
                if (!running) return;
                if (bmp != null) {
                    crossFade(bmp);
                    moveClock(corner);
                }
                handler.postDelayed(next, bmp != null ? SLIDE_MS : 500L);
            });
        });
    }

    private void rescanWhenEmpty() {
        if (!running) return;
        io.execute(() -> {
            List<File> found = scan();
            handler.post(() -> {
                photos.clear();
                photos.addAll(found);
                lastScan = System.currentTimeMillis();
                index = -1;
                showNext();
            });
        });
    }

    /** The two image views never change order (clock and text stay above): the new photo fades in while the old fades out. */
    private void crossFade(Bitmap bmp) {
        ImageView incoming = back;
        ImageView outgoing = front;
        front = incoming;
        back = outgoing;
        incoming.animate().cancel();
        outgoing.animate().cancel();
        incoming.setImageBitmap(bmp);
        incoming.animate().alpha(1f).setDuration(FADE_MS).start();
        outgoing.animate().alpha(0f).setDuration(FADE_MS)
                .withEndAction(() -> outgoing.setImageDrawable(null)).start();
    }

    /** Fades the clock out, moves it to the given corner and fades it back in (nothing if it is already there). */
    private void moveClock(int corner) {
        if (corner == clockCorner) return;
        clockCorner = corner;
        clockBox.animate().cancel();
        clockBox.animate().alpha(0f).setDuration(FADE_MS / 2).withEndAction(() -> {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) clockBox.getLayoutParams();
            lp.gravity = corner;
            clockBox.setLayoutParams(lp);
            int side = (corner & Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.START ? Gravity.START : Gravity.END;
            clockBox.setGravity(side);
            namazLine.setGravity(side);
            clockBox.animate().alpha(1f).setDuration(FADE_MS).start();
        }).start();
    }

    /**
     * The corner of the visible (centre-cropped) picture with the least detail and the least brightness:
     * text, faces and calligraphy have strong edges, and a white clock reads worst on a light patch.
     * Bottom corners win ties, as that is where a clock is expected.
     */
    private int calmestCorner(Bitmap bmp) {
        try {
            float scale = Math.max((float) screenW / bmp.getWidth(), (float) screenH / bmp.getHeight());
            int vw = Math.min(bmp.getWidth(), Math.round(screenW / scale));
            int vh = Math.min(bmp.getHeight(), Math.round(screenH / scale));
            int vx = (bmp.getWidth() - vw) / 2, vy = (bmp.getHeight() - vh) / 2;
            final int W = 96, H = 54;
            Bitmap visible = Bitmap.createBitmap(bmp, vx, vy, vw, vh);
            Bitmap small = Bitmap.createScaledBitmap(visible, W, H, true);
            if (visible != bmp && visible != small) visible.recycle();
            int[] px = new int[W * H];
            small.getPixels(px, 0, W, 0, 0, W, H);
            small.recycle();
            float[] lum = new float[W * H];
            for (int i = 0; i < px.length; i++) {
                int c = px[i];
                lum[i] = 0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255);
            }
            // clock area: about 36% of the width and 34% of the height, in from the edge
            int rw = 35, rh = 20, my = (H - rh) / 2;
            int[][] origins = {{W - rw, H - rh}, {0, H - rh}, {W - rw, 0}, {0, 0}, {W - rw, my}, {0, my}};
            int[] gravities = {Gravity.BOTTOM | Gravity.END, Gravity.BOTTOM | Gravity.START,
                    Gravity.TOP | Gravity.END, Gravity.TOP | Gravity.START,
                    Gravity.CENTER_VERTICAL | Gravity.END, Gravity.CENTER_VERTICAL | Gravity.START};
            float[] bias = {1.0f, 1.04f, 1.12f, 1.15f, 1.06f, 1.1f};
            float best = Float.MAX_VALUE;
            int pick = 0;
            for (int k = 0; k < origins.length; k++) {
                int ox = origins[k][0], oy = origins[k][1];
                float edges = 0, bright = 0;
                int n = 0;
                for (int y = oy; y < oy + rh - 1; y++) {
                    for (int x = ox; x < ox + rw - 1; x++) {
                        float l = lum[y * W + x];
                        edges += Math.abs(l - lum[y * W + x + 1]) + Math.abs(l - lum[(y + 1) * W + x]);
                        bright += l;
                        n++;
                    }
                }
                float score = (edges / n) * 4f + (bright / n) * 0.4f;
                score *= bias[k];
                if (score < best) {
                    best = score;
                    pick = k;
                }
            }
            return gravities[pick];
        } catch (Throwable t) {
            return clockCorner;
        }
    }

    /** Every TVPhotos folder on USB drives and the TV's own storage. */
    static List<File> scan() {
        List<File> dirs = new ArrayList<>();
        File storage = new File("/storage");
        File[] vols = storage.listFiles();
        if (vols != null) {
            for (File v : vols) {
                if (v.getName().equals("self") || v.getName().equals("emulated")) continue;
                dirs.add(new File(v, FOLDER));
            }
        }
        dirs.add(new File("/sdcard/" + FOLDER));
        dirs.add(new File("/sdcard/Pictures/" + FOLDER));
        dirs.add(new File("/storage/emulated/0/" + FOLDER));

        List<File> out = new ArrayList<>();
        for (File d : dirs) collect(d, out, 0);
        // de-duplicate (sdcard and emulated/0 are the same place)
        List<File> unique = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (File f : out) {
            String key = f.getName() + ":" + f.length();
            if (!seen.contains(key)) {
                seen.add(key);
                unique.add(f);
            }
        }
        Collections.shuffle(unique);
        return unique;
    }

    private static void collect(File dir, List<File> out, int depth) {
        if (depth > 3 || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory()) {
                collect(f, out, depth + 1);
            } else {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")) {
                    out.add(f);
                }
            }
        }
    }

    /** Decodes at about screen size (keeps memory low on a 1-2 GB TV) and applies the camera's rotation. */
    private Bitmap decode(File f) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= screenW && o.outHeight / (sample * 2) >= screenH) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            d.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), d);
            if (bmp == null) return null;
            int deg = 0;
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.endsWith(".jpg") || n.endsWith(".jpeg")) {
                int or = new ExifInterface(f.getAbsolutePath())
                        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                if (or == ExifInterface.ORIENTATION_ROTATE_90) deg = 90;
                else if (or == ExifInterface.ORIENTATION_ROTATE_180) deg = 180;
                else if (or == ExifInterface.ORIENTATION_ROTATE_270) deg = 270;
            }
            if (deg != 0) {
                Matrix m = new Matrix();
                m.postRotate(deg);
                Bitmap r = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                if (r != bmp) bmp.recycle();
                bmp = r;
            }
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }
}
