package dev.shizzi

/** Rebuild the downstream first, then confirm its upstream without a second restart. */
fun restartAndConfirmTetheringUpstream(restartHotspot: () -> Unit, checkSelected: () -> Boolean): Boolean {
    restartHotspot()
    return checkSelected()
}
