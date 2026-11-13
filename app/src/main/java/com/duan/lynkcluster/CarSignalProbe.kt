package com.duan.lynkcluster

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyConfig
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * 车机上排查车辆信号用的诊断工具。
 *
 * 输出 CarService 能看到的全部 VehicleProperty（含厂商私有属性），
 * 标出本项目需要的信号是否存在、类型、读写权限、area、取值范围。
 * 领克不同车型/车机版本的属性表差异很大，先跑这个再决定映射。
 */
object CarSignalProbe {

    private val TARGETS = linkedMapOf(
        VehiclePropertyIds.PERF_VEHICLE_SPEED to "车速",
        VehiclePropertyIds.PERF_VEHICLE_SPEED_DISPLAY to "车速（显示）",
        VehiclePropertyIds.ENGINE_RPM to "转速",
        VehiclePropertyIds.ENGINE_COOLANT_TEMP to "水温",
        VehiclePropertyIds.CURRENT_GEAR to "当前挡位",
        VehiclePropertyIds.GEAR_SELECTION to "挡位选择",
        VehiclePropertyIds.TIRE_PRESSURE to "胎压",
        VehiclePropertyIds.FUEL_LEVEL to "油量",
        VehiclePropertyIds.RANGE_REMAINING to "续航",
        VehiclePropertyIds.PERF_ODOMETER to "总里程",
        VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE to "车外温度",
        VehiclePropertyIds.INSTANTANEOUS_FUEL_ECONOMY to "瞬时油耗",
    )

    fun probe(context: Context): String = buildString {
        // 先写不依赖 CarService 的信息：即使连不上，也能确认 APK 装在哪、权限给没给。
        appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("APK: ${context.applicationInfo.sourceDir}")
        appendLine(
            "系统分区: " +
                context.applicationInfo.sourceDir.startsWith("/system/"),
        )
        appendLine()
        appendLine("=== 权限 ===")
        CAR_PERMISSIONS.forEach { permission ->
            val granted = context.checkSelfPermission(permission) ==
                PackageManager.PERMISSION_GRANTED
            appendLine("${if (granted) "✓" else "✗"} $permission")
        }
        appendLine()

        var car: Car? = null
        try {
            car = Car.createCar(context)
            if (car == null || !car.isConnected) {
                appendLine("CarService: 未连接（车机没有 android.car 服务，或应用被拒绝）")
                return@buildString
            }
            appendLine("CarService: 已连接")

            // getCarManager(Class) 是 API 33 才加入的重载，Android 11 上会抛
            // NoSuchMethodError；车机诊断必须使用 API 24+ 就有的字符串入口。
            val manager = car.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            if (manager == null) {
                appendLine("CarPropertyManager: 不可用")
                return@buildString
            }

            val configs = manager.propertyList
            appendLine("属性总数: ${configs.size}")
            if (configs.isEmpty()) {
                appendLine("属性列表为空：VHAL 未接入，或权限白名单未生效。")
                return@buildString
            }
            appendLine()
            appendLine("=== 目标信号 ===")

            val byId = configs.associateBy { it.propertyId }
            TARGETS.forEach { (id, label) ->
                val config = byId[id]
                if (config == null) {
                    appendLine("✗ $label  ${nameOf(id)}  ${hex(id)}  车机未提供")
                } else {
                    appendLine("✓ $label  ${nameOf(id)}  ${hex(id)}")
                    appendLine("    ${describe(config)}")
                }
            }

            appendLine()
            appendLine("=== 全部属性（含厂商私有） ===")
            appendLine("★ = 本项目目标信号")
            configs
                .sortedBy { it.propertyId }
                .take(MAX_LISTED)
                .forEach { config ->
                    val mark = if (TARGETS.containsKey(config.propertyId)) "★" else " "
                    appendLine("$mark ${nameOf(config.propertyId)} ${hex(config.propertyId)}")
                    appendLine("    ${describe(config)}")
                }
            if (configs.size > MAX_LISTED) {
                appendLine("… 还有 ${configs.size - MAX_LISTED} 个属性未列出")
            }
        } catch (t: Throwable) {
            appendLine("诊断中断：${t.javaClass.simpleName}: ${t.message}")
        } finally {
            runCatching { car?.disconnect() }
        }
    }

    /** 不同 Android 版本的 CarPropertyConfig 方法不完全一致，单条属性读失败不影响整张表。 */
    private fun describe(config: CarPropertyConfig<*>): String = runCatching {
        val type = config.propertyType?.simpleName ?: "?"
        val access = when (config.access) {
            1 -> "READ"
            2 -> "WRITE"
            3 -> "READ_WRITE"
            else -> "NONE"
        }
        val areas = config.areaIds?.joinToString(",") { it.toString() } ?: "-"
        "type=$type access=$access area=[$areas] " +
            "min=${config.minValue} max=${config.maxValue} " +
            "rate=${config.maxSampleRate}/s"
    }.getOrElse { "属性详情不可读：${it.javaClass.simpleName}" }

    private fun nameOf(id: Int): String = runCatching {
        VehiclePropertyIds.toString(id)
    }.getOrDefault(hex(id))

    private fun hex(id: Int): String = "0x" + Integer.toHexString(id).uppercase()

    private const val MAX_LISTED = 200

    private val CAR_PERMISSIONS = listOf(
        "android.car.permission.CAR_SPEED",
        "android.car.permission.CAR_POWERTRAIN",
        "android.car.permission.CAR_ENGINE_DETAILED",
        "android.car.permission.CAR_TIRES",
        "android.car.permission.CAR_ENERGY",
        "android.car.permission.CAR_EXTERIOR_ENVIRONMENT",
        "android.car.permission.CAR_MILEAGE",
        "android.car.permission.CAR_INFO",
    )
}
