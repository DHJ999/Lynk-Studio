package com.duan.lynkcluster

import android.os.Bundle
import android.os.IBinder
import android.util.Log
import java.lang.reflect.Method

/**
 * 让 QNX 把物理仪表切到 Android display 2。
 *
 * 只渲染到 display 2 不够：还要通知 DimNaviService 打开 DisplayCluster，
 * 并请求仪表显示导航/投屏区域。厂家服务不校验调用方，普通应用可直接调。
 */
object ClusterProjector {
    private const val TAG = "LynkCluster/Projector"
    private const val SERVICE_NAME = "DimNaviService"
    private const val IFACE = "ecarx.dimprotocol.custom.service.IDimNaviService"

    private const val KEY_SCREEN_READY = "KEY_NAVI_INFO_NAVI_SCREEN_READY_STATUS"
    private const val KEY_FRONT_DESK = "KEY_NAVI_INFO_NAVI_FRONT_DESK_STATUS"

    @Volatile
    private var service: Any? = null

    @Volatile
    private var active = false

    fun isActive(): Boolean = active

    @Synchronized
    fun enable(): Boolean {
        if (active) return true
        return try {
            HiddenApi.allow()
            val binder = serviceBinder()
            if (binder == null) {
                Log.w(TAG, "DimNaviService 不可用")
                return false
            }
            val impl = asInterface(binder)
            service = impl

            // 与实车验证过的顺序一致：NAVI_START -> extension -> project switch
            invoke(
                "notifyNavigationStatus",
                arrayOf<Class<*>>(Int::class.javaPrimitiveType!!),
                2,
            )
            invoke(
                "updateExtensionInfo",
                arrayOf<Class<*>>(Bundle::class.java),
                Bundle().apply {
                    putBoolean(KEY_SCREEN_READY, true)
                    putBoolean(KEY_FRONT_DESK, true)
                },
            )
            invoke(
                "notifyDimProjectScreenSwitch",
                arrayOf<Class<*>>(Boolean::class.javaPrimitiveType!!),
                true,
            )
            active = true
            Log.i(TAG, "物理仪表接管已开启")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "开启物理仪表接管失败", t)
            service = null
            active = false
            false
        }
    }

    @Synchronized
    fun disable() {
        if (!active && service == null) return
        try {
            invoke(
                "notifyNavigationStatus",
                arrayOf<Class<*>>(Int::class.javaPrimitiveType!!),
                3,
            )
            invoke(
                "updateExtensionInfo",
                arrayOf<Class<*>>(Bundle::class.java),
                Bundle().apply {
                    putBoolean(KEY_SCREEN_READY, false)
                    putBoolean(KEY_FRONT_DESK, false)
                },
            )
            invoke(
                "notifyDimProjectScreenSwitch",
                arrayOf<Class<*>>(Boolean::class.javaPrimitiveType!!),
                false,
            )
            Log.i(TAG, "物理仪表接管已关闭")
        } catch (t: Throwable) {
            Log.w(TAG, "关闭物理仪表接管失败", t)
        } finally {
            service = null
            active = false
        }
    }

    private fun invoke(name: String, types: Array<Class<*>>, vararg args: Any?) {
        val target = service ?: error("DimNaviService 未连接")
        val method: Method = target.javaClass.getMethod(name, *types)
        method.invoke(target, *args)
    }

    private fun serviceBinder(): IBinder? = runCatching {
        val manager = Class.forName("android.os.ServiceManager")
        val getService = manager.getDeclaredMethod("getService", String::class.java)
            .apply { isAccessible = true }
        getService.invoke(null, SERVICE_NAME) as? IBinder
    }.getOrNull()

    private fun asInterface(binder: IBinder): Any {
        val iface = Class.forName(IFACE)
        val stub = iface.declaredClasses.firstOrNull { it.simpleName == "Stub" }
            ?: error("IDimNaviService.Stub 不存在")
        val asInterface = stub.getMethod("asInterface", IBinder::class.java)
        return asInterface.invoke(null, binder)
            ?: error("IDimNaviService.asInterface 返回 null")
    }
}
