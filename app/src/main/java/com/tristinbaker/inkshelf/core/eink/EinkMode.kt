package com.tristinbaker.inkshelf.core.eink

/**
 * Display modes exposed by the Kompakt's private `android.meink.IMeinkService`.
 * Values and names come from KompaktX, which is the more trustworthy of the two
 * community implementations: the other one marshals its parcel without the
 * leading package-name string, so the transaction fails silently.
 */
enum class EinkMode(val code: Int, val label: String, val panelCode: String, val description: String) {
    /** 16-level greyscale, 4-bit dithering. Sharpest text, slowest refresh. */
    CONTRAST(1, "Contrast", "GC16", "Sharpest text, slowest refresh"),

    /** 2-level, fastest. Best while scrolling, worst for reading long passages. */
    SPEED(2, "Speed", "A2", "Fastest refresh, blocky text"),

    /** Panel initialisation pass. Not a usable display mode; use for de-ghosting. */
    CLEAR(3, "Full clear", "INIT", "Panel reset, clears ghosting"),

    /** 4-level greyscale. Middle ground between GC16 and A2. */
    LIGHT(4, "Light", "DU", "Softer contrast, moderate refresh"),
    ;

    companion object {
        val DEFAULT = CONTRAST
        val readable = listOf(CONTRAST, SPEED, LIGHT)
        fun fromCode(code: Int): EinkMode? = entries.firstOrNull { it.code == code }
    }
}
