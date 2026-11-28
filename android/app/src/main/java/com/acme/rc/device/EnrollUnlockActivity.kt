package com.acme.rc.device

import android.app.Activity
import android.graphics.Typeface
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
import android.widget.TextView
import android.widget.Toast
import com.acme.rc.R
import com.acme.rc.agent.AgentService

/**
 * Local, explicit enrollment screen for the device unlock credential.
 *
 * The detected type is shown in the centre; PIN, pattern, and password are
 * available as a segmented switcher above the input. The credential is stored
 * with the same Keystore key as the panel flow and never leaves the device.
 */
class EnrollUnlockActivity : Activity() {

    private lateinit var activeHint: TextView
    private lateinit var typePin: TextView
    private lateinit var typePattern: TextView
    private lateinit var typePassword: TextView
    private lateinit var secretGroup: View
    private lateinit var patternGroup: View
    private lateinit var input: EditText
    private lateinit var show: CheckBox
    private lateinit var patternSequence: TextView
    private lateinit var patternPad: PatternPadView
    private lateinit var saveButton: Button
    private lateinit var error: TextView

    private var selectedType = LockType.UNKNOWN
    private var userSelectedType = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        setContentView(R.layout.activity_enroll_unlock)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)

        activeHint = findViewById(R.id.enroll_active_hint)
        typePin = findViewById(R.id.enroll_type_pin)
        typePattern = findViewById(R.id.enroll_type_pattern)
        typePassword = findViewById(R.id.enroll_type_password)
        secretGroup = findViewById(R.id.enroll_secret_group)
        patternGroup = findViewById(R.id.enroll_pattern_group)
        input = findViewById(R.id.enroll_secret)
        show = findViewById(R.id.enroll_show)
        patternSequence = findViewById(R.id.enroll_pattern_sequence)
        patternPad = findViewById(R.id.enroll_pattern_pad)
        saveButton = findViewById(R.id.enroll_save)
        error = findViewById(R.id.enroll_error)

        typePin.setOnClickListener { chooseType(LockType.PIN) }
        typePattern.setOnClickListener { chooseType(LockType.PATTERN) }
        typePassword.setOnClickListener { chooseType(LockType.PASSWORD) }

        show.setOnCheckedChangeListener { _, checked ->
            input.transformationMethod = if (checked) {
                null
            } else {
                PasswordTransformationMethod.getInstance()
            }
            input.setSelection(input.text.length)
        }

        patternPad.onPatternChanged = { updatePatternHint(it) }
        findViewById<TextView>(R.id.enroll_pattern_clear).setOnClickListener {
            patternPad.clear()
        }

        saveButton.setOnClickListener { save() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                save()
                true
            } else {
                false
            }
        }

        selectType(LockTypeDetector.detect(this))
    }

    override fun onResume() {
        super.onResume()
        if (userSelectedType) return
        val detected = LockTypeDetector.detect(this)
        if (detected != LockType.UNKNOWN && detected != selectedType) {
            selectType(detected)
        }
    }

    private fun chooseType(type: LockType) {
        userSelectedType = true
        if (type == selectedType && type != LockType.UNKNOWN) return
        selectType(type)
    }

    private fun selectType(type: LockType) {
        selectedType = type
        applyType(type)
        updateTypeChips()
        updateSaveState()
    }

    private fun applyType(type: LockType) {
        error.visibility = View.GONE
        when (type) {
            LockType.PIN -> {
                secretGroup.visibility = View.VISIBLE
                patternGroup.visibility = View.GONE
                input.inputType = InputType.TYPE_CLASS_NUMBER or
                    InputType.TYPE_NUMBER_VARIATION_PASSWORD
                input.hint = getString(R.string.enroll_pin_hint)
                input.text.clear()
                show.isChecked = false
                activeHint.text = getString(R.string.enroll_pin_prompt)
            }
            LockType.PASSWORD -> {
                secretGroup.visibility = View.VISIBLE
                patternGroup.visibility = View.GONE
                input.inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_PASSWORD
                input.hint = getString(R.string.enroll_password_hint)
                input.text.clear()
                show.isChecked = false
                activeHint.text = getString(R.string.enroll_password_prompt)
            }
            LockType.PATTERN -> {
                secretGroup.visibility = View.GONE
                patternGroup.visibility = View.VISIBLE
                input.text.clear()
                show.isChecked = false
                hideKeyboard()
                patternPad.clear()
                activeHint.text = getString(R.string.enroll_pattern_prompt)
                updatePatternHint("")
            }
            LockType.UNKNOWN -> {
                secretGroup.visibility = View.GONE
                patternGroup.visibility = View.GONE
                input.text.clear()
                show.isChecked = false
                hideKeyboard()
                activeHint.text = getString(R.string.enroll_choose_prompt)
            }
        }
    }

    private fun updateTypeChips() {
        val chips = listOf(
            typePin to LockType.PIN,
            typePattern to LockType.PATTERN,
            typePassword to LockType.PASSWORD
        )
        val activeColor = getColor(R.color.enroll_text)
        val idleColor = getColor(R.color.enroll_muted)
        for ((view, type) in chips) {
            val active = type == selectedType
            view.setTextColor(if (active) activeColor else idleColor)
            view.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            view.isSelected = active
            view.alpha = if (selectedType == LockType.UNKNOWN) 0.9f else 1f
        }
    }

    private fun updateSaveState() {
        val enabled = selectedType != LockType.UNKNOWN
        saveButton.isEnabled = enabled
        saveButton.alpha = if (enabled) 1f else 0.45f
    }

    private fun updatePatternHint(pattern: String) {
        patternSequence.text = if (pattern.isEmpty()) {
            getString(R.string.enroll_pattern_empty)
        } else {
            getString(R.string.enroll_pattern_count, pattern.length)
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
        if (selectedType == LockType.UNKNOWN) return
        val secret = when (selectedType) {
            LockType.PATTERN -> patternPad.sequence
            else -> input.text.toString()
        }
        val valid = when (selectedType) {
            LockType.PIN -> secret.length in 4..16 && secret.all { it.isDigit() }
            LockType.PATTERN -> DevicePin.isValidPattern(secret)
            LockType.PASSWORD -> DevicePin.isValid(secret)
            LockType.UNKNOWN -> false
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
            error.text = getString(R.string.enroll_failed) + " " + describeError(t)
            error.visibility = View.VISIBLE
        }
    }

    private fun describeError(t: Throwable): String {
        val parts = mutableListOf<String>()
        var current: Throwable? = t
        var depth = 0
        while (current != null && depth < 4) {
            current.javaClass.simpleName
                .takeIf { it.isNotBlank() && it !in parts }
                ?.let { parts += it }
            current.message
                ?.trim()
                ?.takeIf { it.isNotBlank() && it !in parts }
                ?.let { parts += it }
            current = current.cause
            depth++
        }
        return if (parts.isEmpty()) "unknown error" else parts.joinToString(": ")
    }
}
