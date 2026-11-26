package com.acme.rc.device

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import com.acme.rc.R
import com.acme.rc.agent.AgentService

/**
 * Local, explicit enrollment screen for the device unlock credential.
 *
 * The input adapts to the credential type cached from the keyguard: a numeric
 * PIN field, a password field, or a 3x3 pattern pad. The operator can override
 * the detected type. This is deliberately not part of the WebView and never
 * sends the credential anywhere.
 */
class EnrollUnlockActivity : Activity() {

    private lateinit var typeGroup: RadioGroup
    private lateinit var detectedHint: TextView
    private lateinit var secretGroup: View
    private lateinit var patternGroup: View
    private lateinit var input: EditText
    private lateinit var show: CheckBox
    private lateinit var patternSequence: TextView
    private lateinit var error: TextView

    private var selectedType = LockType.PASSWORD
    private val sequence = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        setContentView(R.layout.activity_enroll_unlock)

        typeGroup = findViewById(R.id.enroll_type_group)
        detectedHint = findViewById(R.id.enroll_detected_hint)
        secretGroup = findViewById(R.id.enroll_secret_group)
        patternGroup = findViewById(R.id.enroll_pattern_group)
        input = findViewById(R.id.enroll_secret)
        show = findViewById(R.id.enroll_show)
        patternSequence = findViewById(R.id.enroll_pattern_sequence)
        error = findViewById(R.id.enroll_error)

        val save = findViewById<Button>(R.id.enroll_save)
        val cancel = findViewById<Button>(R.id.enroll_cancel)

        show.setOnCheckedChangeListener { _, checked ->
            input.transformationMethod = if (checked) {
                null
            } else {
                PasswordTransformationMethod.getInstance()
            }
            input.setSelection(input.text.length)
        }

        listOf(
            R.id.enroll_dot_1 to '1',
            R.id.enroll_dot_2 to '2',
            R.id.enroll_dot_3 to '3',
            R.id.enroll_dot_4 to '4',
            R.id.enroll_dot_5 to '5',
            R.id.enroll_dot_6 to '6',
            R.id.enroll_dot_7 to '7',
            R.id.enroll_dot_8 to '8',
            R.id.enroll_dot_9 to '9'
        ).forEach { (id, digit) ->
            findViewById<Button>(id).setOnClickListener { appendDot(digit) }
        }
        findViewById<Button>(R.id.enroll_pattern_clear).setOnClickListener {
            sequence.clear()
            updateSequence()
        }

        save.setOnClickListener { save() }
        cancel.setOnClickListener { finish() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                save()
                true
            } else {
                false
            }
        }

        val detected = LockTypeStore.get(this)
        val initial = if (detected == LockType.UNKNOWN) LockType.PASSWORD else detected
        typeGroup.check(
            when (initial) {
                LockType.PIN -> R.id.enroll_type_pin
                LockType.PATTERN -> R.id.enroll_type_pattern
                else -> R.id.enroll_type_password
            }
        )
        detectedHint.text = if (detected == LockType.UNKNOWN) {
            getString(R.string.enroll_unknown)
        } else {
            getString(R.string.enroll_detected, label(detected))
        }
        applyType(initial)

        typeGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.enroll_type_pin -> applyType(LockType.PIN)
                R.id.enroll_type_pattern -> applyType(LockType.PATTERN)
                R.id.enroll_type_password -> applyType(LockType.PASSWORD)
            }
        }
    }

    private fun label(type: LockType): String = when (type) {
        LockType.PIN -> getString(R.string.enroll_type_pin)
        LockType.PATTERN -> getString(R.string.enroll_type_pattern)
        LockType.PASSWORD -> getString(R.string.enroll_type_password)
        LockType.UNKNOWN -> ""
    }

    private fun applyType(type: LockType) {
        selectedType = type
        error.visibility = View.GONE
        when (type) {
            LockType.PATTERN -> {
                secretGroup.visibility = View.GONE
                patternGroup.visibility = View.VISIBLE
                input.text.clear()
                show.isChecked = false
                hideKeyboard()
                updateSequence()
            }
            LockType.PIN -> {
                secretGroup.visibility = View.VISIBLE
                patternGroup.visibility = View.GONE
                input.inputType = InputType.TYPE_CLASS_NUMBER or
                    InputType.TYPE_NUMBER_VARIATION_PASSWORD
                input.hint = getString(R.string.enroll_pin_hint)
                input.text.clear()
                show.isChecked = false
                input.requestFocus()
            }
            LockType.PASSWORD, LockType.UNKNOWN -> {
                secretGroup.visibility = View.VISIBLE
                patternGroup.visibility = View.GONE
                input.inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_PASSWORD
                input.hint = getString(R.string.enroll_password_hint)
                input.text.clear()
                show.isChecked = false
                input.requestFocus()
            }
        }
    }

    private fun appendDot(digit: Char) {
        if (sequence.length >= 9) return
        if (sequence.toString().contains(digit)) return
        sequence.append(digit)
        updateSequence()
    }

    private fun updateSequence() {
        patternSequence.text = if (sequence.isEmpty()) {
            getString(R.string.enroll_pattern_empty)
        } else {
            getString(
                R.string.enroll_pattern_sequence,
                sequence.toString().toCharArray().joinToString("-")
            )
        }
    }

    private fun hideKeyboard() {
        try {
            getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(input.windowToken, 0)
        } catch (_: Throwable) {
            // The IME may already be gone.
        }
    }

    private fun save() {
        val secret = when (selectedType) {
            LockType.PATTERN -> sequence.toString()
            else -> input.text.toString()
        }
        val valid = when (selectedType) {
            LockType.PIN -> secret.length in 4..16 && secret.all { it.isDigit() }
            LockType.PATTERN -> DevicePin.isValidPattern(secret)
            LockType.PASSWORD, LockType.UNKNOWN -> DevicePin.isValid(secret)
        }
        if (!valid) {
            error.text = when (selectedType) {
                LockType.PIN -> getString(R.string.enroll_invalid_pin)
                LockType.PATTERN -> getString(R.string.enroll_invalid_pattern)
                else -> getString(R.string.enroll_invalid_password)
            }
            error.visibility = View.VISIBLE
            return
        }

        try {
            DevicePin.save(this, secret)
            LockTypeStore.set(this, selectedType)
            UnlockEnrollment.markPrompted(this)
            UnlockEnrollment.cancel(this)
            AgentService.instance?.refreshState()
            Toast.makeText(this, R.string.enroll_saved, Toast.LENGTH_SHORT).show()
            finish()
        } catch (t: Throwable) {
            error.text = getString(R.string.enroll_failed) + " " +
                (t.message ?: t.javaClass.simpleName)
            error.visibility = View.VISIBLE
        }
    }
}
