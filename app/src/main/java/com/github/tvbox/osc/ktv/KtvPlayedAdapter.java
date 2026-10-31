package com.github.tvbox.osc.ktv;

import android.graphics.Color;
import android.text.TextUtils;

import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;

/**
 * 已播列表适配器（列表行）：序号统一显示 ✔，点按可重唱
 */
public class KtvPlayedAdapter extends KtvQueueAdapter {

    public KtvPlayedAdapter() {
        super();
        setCurrentIndex(-1);
    }

    @Override
    protected void convert(BaseViewHolder helper, Song item) {
        helper.setText(R.id.tvIndex, "✔");
        helper.setTextColor(R.id.tvIndex, Color.parseColor("#B3FFFFFF"));
        helper.setText(R.id.tvName, TextUtils.isEmpty(item.name) ? "未知歌曲" : item.name);
        helper.setText(R.id.tvArtist, TextUtils.isEmpty(item.artist) ? "" : item.artist);
    }
}
