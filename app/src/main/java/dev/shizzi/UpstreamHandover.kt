package dev.shizzi

/** A failed check may recover once; cancellation/other exceptions must never reopen the AP. */
fun confirmTetheringUpstream(checkSelected: () -> Boolean, recoverOnce: () -> Unit): Boolean {
    if (checkSelected()) return true
    recoverOnce()
    return checkSelected()
}
