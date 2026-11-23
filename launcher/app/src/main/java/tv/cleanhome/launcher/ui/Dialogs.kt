package tv.cleanhome.launcher.ui

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import tv.cleanhome.launcher.R

/** Small D-pad friendly dialogs in the device's default dialog style. */
object Dialogs {
    private const val THEME = android.R.style.Theme_DeviceDefault_Dialog_Alert

    /** A list of actions; OK on an entry closes the dialog and runs the action. */
    fun showMenu(activity: Activity, title: String, actions: List<Pair<String, () -> Unit>>) {
        AlertDialog.Builder(activity, THEME)
            .setTitle(title)
            .setItems(actions.map { it.first }.toTypedArray()) { dialog, which ->
                dialog.dismiss()
                actions[which].second()
            }
            .show()
    }

    /**
     * A single-line text field. OK on the field opens the on-screen keyboard; "Done" on the keyboard
     * or the OK button confirms, BACK cancels. [onConfirm] receives the trimmed text (may be empty).
     */
    fun showTextInput(
        activity: Activity,
        title: String,
        message: String?,
        initialText: String,
        hint: String?,
        onConfirm: (String) -> Unit,
    ) {
        val field = EditText(activity).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setText(initialText)
            setSelectAllOnFocus(true)
            this.hint = hint
        }
        val padding = activity.resources.getDimensionPixelSize(R.dimen.dialog_field_padding)
        val container = FrameLayout(activity).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(field)
        }
        val dialog = AlertDialog.Builder(activity, THEME)
            .setTitle(title)
            .setMessage(message)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ -> onConfirm(field.text.toString().trim()) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
            dialog.dismiss()
            onConfirm(field.text.toString().trim())
            true
        }
        dialog.show()
        field.requestFocus()
    }
}
