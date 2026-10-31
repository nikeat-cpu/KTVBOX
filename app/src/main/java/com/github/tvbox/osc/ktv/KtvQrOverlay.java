package com.github.tvbox.osc.ktv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.github.tvbox.osc.server.ControlManager;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.Random;

/**
 * 电视端「手机扫码点歌」二维码浮层。
 * KtvActivity（点歌台主界面，常驻，每 30 秒在左下/右下角随机换位置）与
 * PlayActivity（播放页，只显示 60 秒后自动隐藏）共用。
 * 播放页模式：每首歌开始时出现（PlayActivity 每首歌新建实例，天然每首一次），
 * 展示期间每 20 秒在左上/右上角轮换一次位置，60 秒后自动隐藏，不再遮挡画面。
 * 白底半透明，二维码下方带「手机扫码点歌」文字，点击弹出土司显示地址。
 */
public class KtvQrOverlay {

    private final Activity activity;
    private final boolean autoMove;
    private LinearLayout container;
    private String url;
    private Handler handler;
    private Runnable moveRunnable;
    private Runnable hideRunnable;

    /** 播放页模式：浮层展示时长（毫秒），到时自动隐藏 */
    private static final long AUTO_HIDE_MS = 60_000;

    /** 主界面模式：浮层换位间隔（毫秒），左下/右下角随机换 */
    private static final long MAIN_MOVE_MS = 30_000;

    private KtvQrOverlay(Activity activity, boolean autoMove) {
        this.activity = activity;
        this.autoMove = autoMove;
    }

    /**
     * 挂载浮层。autoMove=true：播放页模式，每 20 秒在左上/右上角之间轮换；
     * autoMove=false：主界面模式，常驻，每 30 秒在左下/右下角随机换位置。
     * Web 服务刚启动时地址可能还是 0.0.0.0，这里每 500ms 重试一次（最多 40 次），
     * 就绪后自动挂上浮层。返回浮层实例（重试期间返回的实例在挂载成功后才非空）。
     */
    public static KtvQrOverlay attach(final Activity activity, final boolean autoMove) {
        final KtvQrOverlay[] holder = new KtvQrOverlay[1];
        final Handler h = new Handler();
        final Runnable[] retry = new Runnable[1];
        retry[0] = new Runnable() {
            private int tries = 0;
            @Override
            public void run() {
                if (holder[0] != null || activity.isFinishing() || activity.isDestroyed()) return;
                try {
                    ControlManager.get().startServer();
                    KtvQrOverlay o = new KtvQrOverlay(activity, autoMove);
                    if (o.build()) {
                        holder[0] = o;
                        return;
                    }
                } catch (Throwable th) {
                    th.printStackTrace();
                }
                if (++tries <= 40) h.postDelayed(retry[0], 500);
            }
        };
        retry[0].run();
        return holder[0];
    }

    public String getUrl() {
        return url;
    }

    private boolean build() {
        url = ControlManager.get().getAddress(false);
        Log.e("KTVLOG", "QrOverlay build: url=" + url);
        if (url == null || url.contains("0.0.0.0")) return false;
        url = url + "ktv";
        Bitmap qr = makeQrBitmap(url, 300);
        if (qr == null) {
            Log.e("KTVLOG", "QrOverlay build: makeQrBitmap failed");
            return false;
        }
        float density = activity.getResources().getDisplayMetrics().density;
        final int size = (int) (116 * density + 0.5f);
        int pad = (int) (6 * density + 0.5f);
        FrameLayout content = activity.findViewById(android.R.id.content);
        ImageView qrView = new ImageView(activity);
        qrView.setImageBitmap(qr);
        qrView.setScaleType(ImageView.ScaleType.FIT_XY);
        qrView.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        // 容器：二维码 + 「手机扫码点歌」文字，白底半透明
        container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER_HORIZONTAL);
        container.setPadding(pad, pad, pad, pad);
        container.setBackgroundResource(android.R.color.white);
        container.setAlpha(0.85f);
        container.addView(qrView);
        TextView tv = new TextView(activity);
        tv.setText("手机扫码点歌");
        tv.setTextColor(Color.BLACK);
        tv.setTextSize(13);
        tv.setGravity(Gravity.CENTER);
        container.addView(tv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size + pad * 2, FrameLayout.LayoutParams.WRAP_CONTENT);
        container.setLayoutParams(lp);
        container.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(activity, "手机点歌：" + url, Toast.LENGTH_SHORT).show();
            }
        });
        content.addView(container);
        if (autoMove) {
            placeTop(new Random().nextBoolean());
            handler = new Handler();
            moveRunnable = new Runnable() {
                @Override
                public void run() {
                    if (container != null && container.isAttachedToWindow()) placeTop(new Random().nextBoolean());
                    handler.postDelayed(this, 20000);
                }
            };
            handler.postDelayed(moveRunnable, 20000);
            // 每首歌只展示 1 分钟，之后隐藏（直到下一首歌新开播放页再出现）
            hideRunnable = new Runnable() {
                @Override
                public void run() {
                    hide();
                }
            };
            handler.postDelayed(hideRunnable, AUTO_HIDE_MS);
        } else {
            // 主界面模式：初始位置随机，之后每 30 秒在左下/右下角随机换
            placeBottom(new Random().nextBoolean());
            handler = new Handler();
            moveRunnable = new Runnable() {
                @Override
                public void run() {
                    if (container != null && container.isAttachedToWindow()) placeBottom(new Random().nextBoolean());
                    handler.postDelayed(this, MAIN_MOVE_MS);
                }
            };
            handler.postDelayed(moveRunnable, MAIN_MOVE_MS);
        }
        return true;
    }

    /** 固定贴顶，放在左上角或右上角（left=true 左上） */
    private void placeTop(boolean left) {
        if (container == null) return;
        View root = (View) container.getParent();
        if (root == null) return;
        int w = root.getWidth();
        if (w <= 0) {
            container.post(new Runnable() {
                @Override
                public void run() {
                    // 重试时保持当前位置：由布局参数里已记录的 margin 判断，这里重新按当前侧放置
                    FrameLayout.LayoutParams llp = (FrameLayout.LayoutParams) container.getLayoutParams();
                    placeTop(llp.leftMargin <= dip(12));
                }
            });
            return;
        }
        int margin = dip(12);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) container.getLayoutParams();
        lp.topMargin = margin;
        lp.leftMargin = left ? margin : Math.max(margin, w - lp.width - margin);
        container.setLayoutParams(lp);
    }

    /** 固定贴底，放在左下角或右下角（点歌台主界面，left=true 左下） */
    private void placeBottom(boolean left) {
        if (container == null) return;
        View root = (View) container.getParent();
        if (root == null) return;
        // WRAP_CONTENT 布局前 lp.height 是 -2，不能用 lp.height 算 margin（会把浮层推到屏幕外），
        // 必须等容器测量完成后用实测高度
        if (container.getHeight() <= 0) {
            container.post(new Runnable() {
                @Override
                public void run() {
                    FrameLayout.LayoutParams llp = (FrameLayout.LayoutParams) container.getLayoutParams();
                    // 重试时保持当前侧：按已记录的 leftMargin 判断
                    placeBottom(llp.leftMargin <= dip(12));
                }
            });
            return;
        }
        int w = root.getWidth(), h = root.getHeight();
        if (w <= 0 || h <= 0) {
            container.post(new Runnable() {
                @Override
                public void run() {
                    FrameLayout.LayoutParams llp = (FrameLayout.LayoutParams) container.getLayoutParams();
                    placeBottom(llp.leftMargin <= dip(12));
                }
            });
            return;
        }
        int margin = dip(12);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) container.getLayoutParams();
        int ch = container.getHeight();
        lp.leftMargin = left ? margin : Math.max(margin, w - lp.width - margin);
        lp.topMargin = Math.max(margin, h - ch - margin);
        container.setLayoutParams(lp);
        Log.e("KTVLOG", "QrOverlay placed: root=" + w + "x" + h + " container=" + lp.width + "x" + ch
                + " left=" + left + " margin=(" + lp.leftMargin + "," + lp.topMargin + ")");
    }

    private int dip(float v) {
        return (int) (v * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 播放页模式到时隐藏：停掉轮换定时器并隐藏视图（保留容器，detach 时统一移除） */
    private void hide() {
        if (handler != null && moveRunnable != null) handler.removeCallbacks(moveRunnable);
        if (container != null) container.setVisibility(View.GONE);
        Log.e("KTVLOG", "QrOverlay auto hidden after " + AUTO_HIDE_MS + "ms");
    }

    public void detach() {
        if (handler != null) {
            if (moveRunnable != null) handler.removeCallbacks(moveRunnable);
            if (hideRunnable != null) handler.removeCallbacks(hideRunnable);
        }
        if (container != null) {
            ViewGroup parent = (ViewGroup) container.getParent();
            if (parent != null) parent.removeView(container);
            container = null;
        }
    }

    private static Bitmap makeQrBitmap(String text, int size) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size);
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    bmp.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bmp;
        } catch (Throwable th) {
            th.printStackTrace();
            return null;
        }
    }
}
