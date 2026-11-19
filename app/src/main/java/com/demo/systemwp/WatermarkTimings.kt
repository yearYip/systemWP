package com.demo.systemwp

/** 一次解析任务独占；多候选或多帧的同名阶段累计，未执行的阶段保持缺省。 */
class WatermarkTimings {
    private val elapsed = mutableMapOf<String, Double>()

    fun record(stage: String, milliseconds: Double) {
        elapsed[stage] = (elapsed[stage] ?: 0.0) + milliseconds
    }

    fun milliseconds(stage: String): Double? = elapsed[stage]

    fun add(other: WatermarkTimings?) {
        other?.elapsed?.forEach { (stage, milliseconds) -> record(stage, milliseconds) }
    }
}
