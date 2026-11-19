package com.demo.systemwp

/** 进度表示当前解析步骤，不估算剩余时间；录屏按当前帧显示。 */
data class WatermarkParseProgress(
    val stage: Stage,
    val detail: String = "",
    val frameIndex: Int? = null,
    val frameCount: Int? = null,
    val skippedStages: Set<Stage> = emptySet()
) {
    enum class Stage {
        READ_IMAGE, DIRECT_READ, HEADER_PROBE, SPECTRAL_LOCATION,
        ORIGINAL_SEARCH, SCALED_SEARCH, ROTATION_SEARCH, SCREEN_COMPENSATION;

        val number: Int get() = ordinal + 1
    }
}
