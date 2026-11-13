package com.duan.lynkcluster

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * VDBus 数据通道探针 + 集群布局（BAP）信号扫描。
 *
 * 背景：`com.desaysv.engmode` 的 ETMDATA 页能显示转速/水温/燃油，走的是 VDBus：
 *
 *   ServiceManager 里注册了 3 个 IVDBus 服务（普通调用方可达）：
 *     com.desaysv.ivi.vds.vehicle.service.VehicleService   ← 车辆
 *     com.desaysv.ivi.vds.vdev.service.VehicleDevice
 *     com.desaysv.ivi.vds.cabinlan.service.CabinLanService
 *
 *   IVDBus 事务：1=get(VDEvent)→VDEvent  2=set(VDEvent)  3=subscribe(...)
 *   VDEvent = { int mId; Bundle mPayload; int mThreadType; long mTimeMillis }
 *
 * 本轮目的（对应 日志.md §K/§N）：
 *  1. 全量扫 `assets/sv.txt` 里的 303 个 SV_* id，把「有 payload 的」列出来 —— 找转速/水温/车速。
 *  2. 重点看 BAP 一族的「视图/布局」信号（QNX 仪表左侧/右侧面板由这些信号决定）：
 *       SV_BAP_NAVI_SET_UPDATE_MAP_VIEW_AND_ORIENTATION 41759
 *       SV_BAP_NAVI_RESP_MAP_VIEW_AND_ORIENTATION      41760
 *       SV_BAP_VPS_SET_DISPLAY                         41774
 *       SV_BAP_CAR_PARKING_UPDATE_VIEW_OPTION          41783
 *       SV_BAP_CLOCK_UPDATE_VIEW_OPTION                41784
 *       SV_BAP_UNIMASTER_UPDATE_VIEW_OPTION            41785
 *     这几条是「能不能改两侧」最直接的抓手：QNX 只揭中间 930px 槽（§K2），
 *     Android 侧唯一的翻盘机会就是让 QNX 自己换布局。
 *
 * 用法：
 *   adb shell am start -n com.duan.lynkcluster/.MainActivity          # 必须先到前台
 *   adb shell am start-service -n com.duan.lynkcluster/.VdBusProbeService
 *   adb logcat -s LynkCluster/VdBus:V
 *
 * 也可只扫指定 id：
 *   adb shell am start-service -n com.duan.lynkcluster/.VdBusProbeService \
 *       -e ids "41759,41760,41774,41783,41785"
 */
class VdBusProbeService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val extra = intent?.getStringExtra("ids")
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.isNotEmpty() }
        val setId = intent?.getIntExtra("setId", -1) ?: -1
        val setKey = intent?.getStringExtra("setKey")
        val setVals = intent?.getStringExtra("setVals")
        val setService = intent?.getStringExtra("setService") ?: SERVICES.first()
        val revert = intent?.getBooleanExtra("revert", true) ?: true

        Thread({
            if (setId > 0) {
                runSetExperiment(setService, setId, setKey, setVals, revert)
            } else {
                runProbe(extra)
            }
        }, "VdBusProbe").apply { isDaemon = true }.start()
        return START_NOT_STICKY
    }

    /**
     * 可逆写入实验：读原值 → 写新值 → 再读 → 写回原值。
     *
     * 只允许改**单个** id 的**单个** int 向量字段，避免一次实验把多个状态改乱。
     * 用法：
     *   adb shell am start-service -n com.duan.lynkcluster/.VdBusProbeService \
     *       -e setService com.desaysv.ivi.vds.vehicle.service.VehicleService \
     *       --ei setId 41783 -e setKey IntVector -e setVals "1" --ez revert true
     */
    private fun runSetExperiment(
        service: String,
        setId: Int,
        setKey: String?,
        setVals: String?,
        revert: Boolean,
    ) {
        HiddenApi.allow()
        val binder = serviceBinder(service)
        if (binder == null) {
            Log.w(TAG, "✗ $service 不可用，写入实验中止")
            stopSelf()
            return
        }
        Log.i(TAG, "=== 写入实验 service=$service id=$setId key=$setKey vals=$setVals revert=$revert")

        val before = getBundle(binder, setId)
        Log.i(TAG, "[before] $setId → ${dumpBundle(before)}")

        val payload = Bundle().apply {
            if (setKey != null && setVals != null) {
                val ints = setVals.split(',').mapNotNull { it.trim().toIntOrNull() }.toIntArray()
                putIntArray(setKey, ints)
            }
        }
        Log.i(TAG, "[set] $setId payload=${dumpBundle(payload)} → ${callSet(binder, setId, payload)}")
        sleepQuietly(400)

        Log.i(TAG, "[after ] $setId → ${dumpBundle(getBundle(binder, setId))}")

        if (revert) {
            sleepQuietly(1200)
            Log.i(TAG, "[revert] $setId → ${callSet(binder, setId, before)}")
            sleepQuietly(400)
            Log.i(TAG, "[final ] $setId → ${dumpBundle(getBundle(binder, setId))}")
        }
        Log.i(TAG, "=== 写入实验结束（物理屏变化请拍照对照）")
        stopSelf()
    }

    private fun sleepQuietly(ms: Long) = runCatching { Thread.sleep(ms) }

    /** 只取 payload，不拼字符串，便于原样写回。 */
    private fun getBundle(binder: IBinder, id: Int): Bundle? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(1)
            data.writeInt(id)
            data.writeBundle(null)
            data.writeInt(0)
            data.writeLong(System.currentTimeMillis())
            if (!binder.transact(TRANSACTION_GET, data, reply, 0)) return null
            if (reply.readInt() != 0) return null            // exception
            if (reply.readInt() == 0) return null            // null
            reply.readInt()                                  // rid
            reply.readBundle(javaClass.classLoader)
        } catch (t: Throwable) {
            Log.w(TAG, "getBundle 失败 ${t.javaClass.simpleName}: ${t.message}")
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** 调 IVDBus.set(VDEvent)（事务 2），返回结果描述。 */
    private fun callSet(binder: IBinder, id: Int, payload: Bundle?): String {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(1)                                  // VDEvent 非空
            data.writeInt(id)                                 // mId
            data.writeBundle(payload)                         // mPayload
            data.writeInt(0)                                  // mThreadType
            data.writeLong(System.currentTimeMillis())        // mTimeMillis
            val ok = binder.transact(TRANSACTION_SET, data, reply, 0)
            val ex = runCatching { reply.readInt() }.getOrDefault(0)
            "transact=$ok exception=$ex"
        } catch (t: Throwable) {
            "失败 ${t.javaClass.simpleName}: ${t.message}"
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun runProbe(onlyIds: List<Int>?) {
        HiddenApi.allow()

        val svList: List<Pair<String, Int>> = onlyIds?.map { "arg$it" to it } ?: loadSvList()
        Log.i(TAG, "扫描 id 数=${svList.size}（sv.txt 共 ${svList.size} 条）")

        for (name in SERVICES) {
            val binder = serviceBinder(name)
            if (binder == null) {
                Log.w(TAG, "✗ $name 不可用")
                continue
            }
            Log.i(TAG, "✓ $name descriptor=${runCatching { binder.interfaceDescriptor }.getOrNull()}")

            // 1) 重点 id：payload 全量展开
            for (id in FOCUS_IDS) {
                Log.i(TAG, "  [focus] $name get($id) → ${callGet(binder, id)}")
            }

            // 2) 全量：只抱回「有内容」的
            var hits = 0
            for ((svName, id) in svList) {
                val r = callGet(binder, id)
                if (r.startsWith("id=0 ") || r.startsWith("返回 null") ||
                    r.startsWith("transact=false") || r.startsWith("exception") ||
                    r.startsWith("失败")
                ) {
                    continue
                }
                hits++
                Log.i(TAG, "  [sv] $svName($id) → $r")
            }
            Log.i(TAG, "  $name 有内容 id 数=$hits / ${svList.size}")
        }

        Log.i(TAG, "探针结束")
        stopSelf()
    }

    /** 从 assets/sv.txt（每行 `SV_NAME\tID`）读信号表。 */
    private fun loadSvList(): List<Pair<String, Int>> = runCatching {
        assets.open("sv.txt").bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val t = line.trim()
                if (t.isEmpty()) return@mapNotNull null
                val p = t.split('\t', ' ', ';').filter { it.isNotBlank() }
                val id = p.lastOrNull()?.toIntOrNull() ?: return@mapNotNull null
                val n = p.firstOrNull() ?: "SV_$id"
                n to id
            }.toList()
        }
    }.getOrElse {
        Log.w(TAG, "sv.txt 读取失败：${it.message}，退回内置候选表")
        FOCUS_IDS.map { "SV_$it" to it }
    }

    private fun serviceBinder(name: String): IBinder? = runCatching {
        val sm = Class.forName("android.os.ServiceManager")
        val m = sm.getDeclaredMethod("getService", String::class.java).apply { isAccessible = true }
        (m.invoke(null, name) as? IBinder)?.takeIf { it.isBinderAlive }
    }.getOrNull()

    /**
     * 调 IVDBus.get(VDEvent)。
     *
     * AIDL 生成的 marshalling：writeInterfaceToken → writeInt(1) → event.writeToParcel()
     * writeToParcel 按字段声明顺序写：mId(int) / mPayload(Bundle) / mThreadType(int) / mTimeMillis(long)。
     */
    private fun callGet(binder: IBinder, id: Int): String {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(1)                                  // VDEvent 非空
            data.writeInt(id)                                 // mId
            data.writeBundle(null)                            // mPayload
            data.writeInt(0)                                  // mThreadType
            data.writeLong(System.currentTimeMillis())        // mTimeMillis

            if (!binder.transact(TRANSACTION_GET, data, reply, 0)) {
                return "transact=false"
            }
            val ex = reply.readInt()
            if (ex != 0) return "exception(code=$ex)"

            val nonNull = reply.readInt()
            if (nonNull == 0) return "返回 null"

            val rid = reply.readInt()
            val payload = reply.readBundle(javaClass.classLoader)
            val threadType = reply.readInt()
            val timeMillis = reply.readLong()

            "id=$rid thread=$threadType t=$timeMillis ${dumpBundle(payload)}"
        } catch (t: Throwable) {
            "失败 ${t.javaClass.simpleName}: ${t.message}"
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** 把 payload 里的每个 key 连值和类型一起打出来。 */
    private fun dumpBundle(b: Bundle?): String {
        if (b == null) return "payload=null"
        val sb = StringBuilder()
        for (k in b.keySet()) {
            val v = b.get(k)
            sb.append(k).append(':').append(typeName(v)).append('=')
            when (v) {
                is IntArray -> sb.append(v.joinToString(",", "[", "]"))
                is LongArray -> sb.append(v.joinToString(",", "[", "]"))
                is DoubleArray -> sb.append(v.joinToString(",", "[", "]"))
                is FloatArray -> sb.append(v.joinToString(",", "[", "]"))
                is ByteArray -> sb.append(v.joinToString(",", "[", "]"))
                is BooleanArray -> sb.append(v.joinToString(",", "[", "]"))
                is Array<*> -> sb.append(v.joinToString(",", "[", "]") { it.toString() })
                is Bundle -> sb.append('{').append(dumpBundle(v)).append('}')
                else -> sb.append(v.toString())
            }
            sb.append("; ")
        }
        return sb.toString().ifBlank { "payload=空" }
    }

    private fun typeName(v: Any?): String = when (v) {
        null -> "null"
        is IntArray -> "IntArray"
        is LongArray -> "LongArray"
        is DoubleArray -> "DoubleArray"
        is FloatArray -> "FloatArray"
        is ByteArray -> "ByteArray"
        is BooleanArray -> "BooleanArray"
        is Array<*> -> "Array"
        is Bundle -> "Bundle"
        else -> v.javaClass.simpleName
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val TAG = "LynkCluster/VdBus"
        const val DESCRIPTOR = "com.desaysv.ivi.vdb.IVDBus"
        const val TRANSACTION_GET = 1
        const val TRANSACTION_SET = 2

        val SERVICES = listOf(
            "com.desaysv.ivi.vds.vehicle.service.VehicleService",
            "com.desaysv.ivi.vds.vdev.service.VehicleDevice",
            "com.desaysv.ivi.vds.cabinlan.service.CabinLanService",
        )

        /** 重点信号：数据（转速/水温/车速）+ 布局（BAP 视图选项）。 */
        val FOCUS_IDS = intArrayOf(
            29519, 61440, 31744, 29536, 20758, 63387,   // 数据候选（§N5）
            41755, 41756, 41757, 41758, 41759, 41760,   // BAP NAVI map view/color/scale
            41761, 41762, 41774, 41783, 41784, 41785,   // BAP VPS/view option
            33323, 34115,                               // ENG screen type
        )
    }
}
