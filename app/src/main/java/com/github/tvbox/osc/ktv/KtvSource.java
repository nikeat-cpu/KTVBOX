package com.github.tvbox.osc.ktv;

import com.github.tvbox.osc.api.ApiConfig;import com.github.tvbox.osc.bean.SourceBean;

import java.util.ArrayList;
import java.util.List;

/**
 * KTV 点歌固定使用的内容源：王二小放牛娃接口里的「KTV┃音乐」
 * 其他源一律不参与 KTV 搜索与点歌。
 */
public final class KtvSource {

    /** 配置 JSON 中该源的 site key */
    public static final String KEY = "MusicAiIKtv";

    private KtvSource() {
    }

    /** 从已加载的配置里取出 KTV┃音乐 源；未配置/未加载时返回 null */
    public static SourceBean get() {
        try {
            List<SourceBean> all = ApiConfig.get().getSourceBeanList();
            if (all == null) return null;
            for (SourceBean bean : all) {
                if (bean != null && KEY.equals(bean.getKey())) return bean;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 同 get()，但包装成列表方便遍历调用方 */
    public static List<SourceBean> list() {
        List<SourceBean> result = new ArrayList<>();
        SourceBean bean = get();
        if (bean != null) result.add(bean);
        return result;
    }

    /** 该源是否可用（已配置） */
    public static boolean available() {
        return get() != null;
    }
}
