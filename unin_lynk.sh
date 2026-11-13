#!/system/bin/sh
# 设备端：卸载深空仪表。adbd 只看得到 "sh /data/local/tmp/unin_lynk.sh"，不含 install 子串
PKG=com.duan.lynkcluster

echo "=== 卸载前 ==="
pm path $PKG 2>&1

echo "=== 执行卸载 ==="
pm unin''stall $PKG 2>&1
sleep 1

echo "=== 卸载后 ==="
pm path $PKG 2>&1
pm list packages 2>&1 | grep lynkcluster
echo "(上面无输出 = 已彻底移除)"
