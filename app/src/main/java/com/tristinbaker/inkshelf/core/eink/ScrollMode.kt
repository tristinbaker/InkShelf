package com.tristinbaker.inkshelf.core.eink

/** How a swipe moves a list. */
enum class ScrollMode(val label: String, val description: String) {
    /**
     * One swipe, one screen. The panel redraws once per page instead of
     * smearing through every intermediate frame of a drag, which is the
     * difference between readable and not on e-ink.
     */
    PAGE("Page", "A swipe turns a whole screen. Clearest on e-ink"),

    /** The list follows the finger, as on any phone. */
    SMOOTH("Smooth", "The list follows your finger"),
}
