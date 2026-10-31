#!/bin/bash
# KTV 全链路自动测试：自动重连投影仪，逐环节验证并输出结果
export PATH="/c/Users/TaoPC/.workbuddy/binaries/PortableGit/versions/1.2.0/usr/bin:/c/Users/TaoPC/.workbuddy/binaries/PortableGit/versions/1.2.0/bin:/c/Windows/System32:$PATH"
ADB=/d/AndroidEnv/sdk/platform-tools/adb.exe
PKG=com.github.tvbox.osc.tk
IP=192.168.1.131
R=/d/Box/selftest

mkdir -p $R
connect() {
  for i in $(seq 1 20); do
    $ADB connect $IP:5555 2>/dev/null
    if $ADB -s $IP:5555 shell true >/dev/null 2>&1; then return 0; fi
    sleep 4
  done
  return 1
}

echo "[0] ping 投影仪..."
ping -n 3 $IP 2>&1 | grep -E "TTL|超时|timed" | head -3

echo "[1] 等待 adb 可连..."
connect || { echo "ADB 20次重连失败"; exit 1; }
echo "    adb OK"

# 确保 app 前台存活
PID=$($ADB -s $IP:5555 shell pidof $PKG | tr -d '\r')
if [ -z "$PID" ]; then
  echo "[2] app 未运行，拉起 KtvActivity..."
  $ADB -s $IP:5555 shell am start -n $PKG/com.github.tvbox.osc.ktv.KtvActivity
  sleep 12
else
  echo "[2] app 已在运行 PID=$PID，拉到前台..."
  $ADB -s $IP:5555 shell am start -n $PKG/com.github.tvbox.osc.ktv.KtvActivity
  sleep 3
fi
PID=$($ADB -s $IP:5555 shell pidof $PKG | tr -d '\r')
echo "    PID=$PID $( [ -n "$PID" ] && echo 存活 || echo 已死 )"

echo "[3] 等配置+jar加载 25s..."
sleep 25

echo "[4] /ktv 手机页面..."
for t in 1 2 3; do
  SZ=$(curl -s --noproxy '*' -m 15 -o $R/ktv_page.html -w "%{size_download}" http://$IP:9978/ktv 2>/dev/null)
  [ -n "$SZ" ] && [ "$SZ" != "0" ] && { echo "    页面 $SZ bytes OK"; break; }
  echo "    第${t}次失败(size=$SZ)，重连后重试..."; connect; sleep 3
done

echo "[5] 搜索 周杰伦..."
for t in 1 2 3 4; do
  curl -s --noproxy '*' -m 60 "http://$IP:9978/ktv/search?wd=%E5%91%A8%E6%9D%B0%E4%BC%A6" -o $R/search.json 2>/dev/null
  if [ -s $R/search.json ]; then
    N=$(grep -o '"name":"[^"]*"' $R/search.json | wc -l)
    echo "    搜到 $N 首:"; grep -o '"name":"[^"]*"' $R/search.json | head -8
    break
  fi
  echo "    第${t}次失败，重连后重试..."; connect; sleep 3
  # app 可能被杀，重新拉起
  $ADB -s $IP:5555 shell am start -n $PKG/com.github.tvbox.osc.ktv.KtvActivity >/dev/null 2>&1
  sleep 8
done

echo "[6] 清空队列后点歌 周杰伦(傲艺)..."
curl -s --noproxy '*' -m 15 "http://$IP:9978/ktv/clear" >/dev/null 2>&1
for t in 1 2 3; do
  RST=$(curl -s --noproxy '*' -m 20 "http://$IP:9978/ktv/add?id=60087140%24%24%24%E5%91%A8%E6%9D%B0%E4%BC%A6%24%24%24%E5%82%B2%E8%89%BA&sourceKey=MusicAiIKtv&name=%E5%91%A8%E6%9D%B0%E4%BC%A6&artist=%E5%82%B2%E8%89%BA" 2>/dev/null)
  [ -n "$RST" ] && { echo "    add返回: $(echo $RST | head -c 200)"; break; }
  echo "    第${t}次失败，重连后重试..."; connect; sleep 3
done

echo "[7] 等 20s 看是否自动进入全屏播放..."
sleep 20
$ADB -s $IP:5555 shell dumpsys activity activities 2>/dev/null | grep mResumedActivity

echo "[8] 播放器日志(最后30条相关)..."
$ADB -s $IP:5555 logcat -d -v time 2>/dev/null | grep -E "17034|17678|TvPlay|IJKMEDIAYM.*(start|prepare)|PlayActivity|FATAL" | grep -vE "sky_prop|SkyData|AndroidAutoSize|WifiVendor" | tail -30 > $R/play_check.txt
head -30 $R/play_check.txt

echo "[9] 队列状态..."
curl -s --noproxy '*' -m 15 "http://$IP:9978/ktv/queue" 2>/dev/null | head -c 300
echo
echo "=== 测试完成 ==="
