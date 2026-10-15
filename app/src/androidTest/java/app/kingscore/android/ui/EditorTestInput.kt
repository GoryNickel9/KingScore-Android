package app.kingscore.android.ui

import android.app.Activity
import android.view.inputmethod.InputMethodManager

/** Settle the text-input session before a test scrolls to and taps an editor action. */
internal fun Activity.finishEditorTestInput() {
    getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    currentFocus?.clearFocus()
}
