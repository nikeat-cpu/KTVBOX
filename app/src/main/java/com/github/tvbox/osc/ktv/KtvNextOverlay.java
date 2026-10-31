package com.github.tvbox.osc.ktv;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 电视端「下一首预告」浮层：当前 KTV 歌曲只剩最后 30 秒时出现，
 * 显示队列里下一首要唱的歌，约 20 秒后自动消失。
 * 无背景纯文字（黑描边影子保证亮画面下可读），显示在屏幕上方居中，
 * 不遮挡底部的歌词字幕。
 */
public class KtvNextOverlay {

    /** 预告展示时长（毫秒） */
    private static final long SHOW_MS = 20_000;

    private final Activity activity;
    private LinearLayout container;
    private TextView tvText;
    private Handler handler;
    private Runnable hideRunnable;

    private KtvNextOverlay(Activity activity) {
        this.activity = activity;
    }

    /** 挂载浮层（构建视图但不显示）。失败返回 null。 */
    public static KtvNextOverlay attach(Activity activity) {
        try {
            KtvNextOverlay o = new KtvNextOverlay(activity);
            if (o.build()) return o;
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return null;
    }

    private boolean build() {
        float density = activity.getResources().getDisplayMetrics().density;
        int margin = (int) (14 * density + 0.5f);

        container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER);

        tvText = new TextView(activity);
        tvText.setTextColor(Color.rgb(192, 255, 255));
        tvText.setTextSize(22);
        tvText.setGravity(Gravity.CENTER);
        // 黑色影子描边：无背景时保证在明亮画面上依然可读
        tvText.setShadowLayer(6, 2, 2, Color.BLACK);
        container.addView(tvText);

        FrameLayout content = activity.findViewById(android.R.id.content);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = margin;
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        container.setLayoutParams(lp);
        container.setVisibility(View.GONE);
        content.addView(container);
        return true;
    }

    /** 显示下一首预告（幂等：显示中重复调用只刷新文字与倒计时） */
    public void show(final Song next) {
        if (container == null || activity.isFinishing() || activity.isDestroyed()) return;
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (container == null) return;
                String name = next.name == null ? "" : next.name;
                String artist = next.artist == null ? "" : next.artist;
                String text = "下一首：" + name;
                if (!artist.isEmpty()) text += " · " + artist;
                tvText.setText(text);
                container.setVisibility(View.VISIBLE);
                if (handler == null) handler = new Handler();
                if (hideRunnable != null) handler.removeCallbacks(hideRunnable);
                hideRunnable = new Runnable() {
                    @Override
                    public void run() {
                        hide();
                    }
                };
                handler.postDelayed(hideRunnable, SHOW_MS);
            }
        });
    }

    private void hide() {
        if (container != null) container.setVisibility(View.GONE);
    }

    public void detach() {
        if (handler != null && hideRunnable != null) handler.removeCallbacks(hideRunnable);
        if (container != null) {
            ViewGroup parent = (ViewGroup) container.getParent();
            if (parent != null) parent.removeView(container);
            container = null;
        }
    }
}
