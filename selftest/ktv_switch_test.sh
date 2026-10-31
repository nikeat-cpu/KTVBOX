#!/bin/bash
# KTV 切歌清场验证：点两首歌，确认旧播放被结束、只有 1 个 PlayActivity
ADB_BIN=/d/AndroidEnv/sdk/platform-tools/adb.exe
BASE=http://127.0.0.1:19978
DEV=192.168.1.31:5555

$ADB_BIN start-server >/dev/null 2>&1
$ADB_BIN disconnect $DEV >/dev/null 2>&1
sleep 1
$ADB_BIN connect $DEV >/dev/null 2>&1
sleep 2
$ADB_BIN -s $DEV forward --remove tcp:19978 >/dev/null 2>&1
$ADB_BIN -s $DEV forward tcp:19978 tcp:9978 >/dev/null 2>&1
sleep 1

echo "=== 0) app 状态 ==="
$ADB_BIN -s $DEV shell "pidof com.github.tvbox.osc.tk" 2>/dev/null
$ADB_BIN -s $DEV shell "dumpsys activity activities | grep mResumedActivity" 2>/dev/null

echo "=== 1) 清队列 ==="
curl -s --noproxy '*' -m 8 "$BASE/ktv/clear" | head -c 60; echo

echo "=== 2) 搜索(轮询最多24次) ==="
GOT=0
for t in $(seq 1 24); do
  curl -s --noproxy '*' -m 8 "$BASE/ktv/search?wd=%E5%91%A8%E6%9D%B0%E4%BC%A6" -o /d/Box/st_search.json 2>/dev/null
  if [ -s /d/Box/st_search.json ] && grep -q '"id"' /d/Box/st_search.json 2>/dev/null; then
    GOT=1; echo "第${t}轮拿到结果"; break
  fi
  sleep 2
done
if [ $GOT -ne 1 ]; then echo "搜索失败，结束"; exit 1; fi

python -c "
import json, urllib.parse
d = json.load(open('D:/Box/st_search.json', encoding='utf-8'))
songs = d.get('list', [])[:2]
out = []
for s in songs:
    out.append(urllib.parse.quote(str(s['id']), safe='') + '|' + urllib.parse.quote(s.get('sourceKey','') or '', safe='') + '|' + urllib.parse.quote(s['name'], safe='') + '|' + urllib.parse.quote(s.get('artist','') or '', safe=''))
open('D:/Box/st_songs.txt', 'w').write('\n'.join(out))
print('取到', len(out), '首')
" || { echo "解析失败"; exit 1; }

S1=$(sed -n '1p' /d/Box/st_songs.txt); S2=$(sed -n '2p' /d/Box/st_songs.txt)
I1=$(echo "$S1" | cut -d'|' -f1); K1=$(echo "$S1" | cut -d'|' -f2); N1=$(echo "$S1" | cut -d'|' -f3); A1=$(echo "$S1" | cut -d'|' -f4)
I2=$(echo "$S2" | cut -d'|' -f1); K2=$(echo "$S2" | cut -d'|' -f2); N2=$(echo "$S2" | cut -d'|' -f3); A2=$(echo "$S2" | cut -d'|' -f4)
echo "歌1: $I1 ; 歌2: $I2"

count_pa() {
  $ADB_BIN -s $DEV shell "dumpsys activity activities 2>/dev/null | grep -c 'com.github.tvbox.osc.tk/com.github.tvbox.osc.ui.activity.PlayActivity'" 2>/dev/null | tr -d '\r'
}
front() {
  $ADB_BIN -s $DEV shell "dumpsys activity activities 2>/dev/null | grep mResumedActivity" 2>/dev/null | tr -d '\r'
}

echo "=== 3) 点歌1 ==="
curl -s --noproxy '*' -m 10 "$BASE/ktv/add?id=$I1&sourceKey=$K1&name=$N1&artist=$A1" | head -c 100; echo
echo "等 25s 让电视进入播放..."
sleep 25
echo "前台: $(front)"
echo "PlayActivity 实例数: $(count_pa)"

echo "=== 4) 立即切歌2（模拟'提前播放列表歌曲'） ==="
curl -s --noproxy '*' -m 10 "$BASE/ktv/play?index=1" | head -c 100; echo
echo "等 30s..."
sleep 30
echo "前台: $(front)"
echo "PlayActivity 实例数(关键指标,应为1): $(count_pa)"

echo "=== 5) 音轨切换接口冒烟(播放中) ==="
curl -s --noproxy '*' -m 10 "$BASE/ktv/track?t=2" | head -c 120; echo
curl -s --noproxy '*' -m 10 "$BASE/ktv/track?t=1" | head -c 120; echo

echo "=== DONE ==="
