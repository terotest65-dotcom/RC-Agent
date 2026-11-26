package com.acme.rc.device

import android.app.Activity
import android.os.Bundle
import android.text.method.PasswordTransformationMethod
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.acme.rc.R
import com.acme.rc.agent.AgentService

/**
 * Local, explicit enrollment screen for the device unlock credential.
 *
 * This is deliberately not part of the WebView and never sends the credential
 * anywhere. It uses the same Keystore-backed store as the admin-panel flow.
 */
class EnrollUnlockActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        setContentView(R.layout.activity_enroll_unlock)

        val input = findViewById<EditText>(R.id.enroll_secret)
        val show = findViewById<CheckBox>(R.id.enroll_show)
        val error = findViewById<TextView>(R.id.enroll_error)
        val save = findViewById<Button>(R.id.enroll_save)
        val cancel = findViewById<Button>(R.id.enroll_cancel)

        input.requestFocus()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)

        show.setOnCheckedChangeListener { _, checked ->
            input.transformationMethod = if (checked) {
                null
            } else {
                PasswordTransformationMethod.getInstance()
            }
            input.setSelection(input.text.length)
        }

        save.setOnClickListener { save(input, error) }
        cancel.setOnClickListener { finish() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                save(input, error)
                true
            } else {
                false
            }
        }
    }

    private fun save(input: EditText, error: TextView) {
        val secret = input.text.toString()
        if (!DevicePin.isValid(secret)) {
            error.text = getString(
                R.string.enroll_invalid,
                DevicePin.MIN_LENGTH,
                DevicePin.MAX_LENGTH
            )
            error.visibility = TextView.VISIBLE
            return
        }

        try {
            DevicePin.save(this, secret)
            UnlockEnrollment.markPrompted(this)
            UnlockEnrollment.cancel(this)
            AgentService.instance?.refreshState()
            Toast.makeText(this, R.string.enroll_saved, Toast.LENGTH_SHORT).show()
            finish()
        } catch (t: Throwable) {
            error.text = getString(R.string.enroll_failed) + " " +
                (t.message ?: t.javaClass.simpleName)
            error.visibility = TextView.VISIBLE
        }
    }
}
