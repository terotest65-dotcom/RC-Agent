package com.acme.rc.device

import android.content.Context
import android.graphics.PointF
import org.json.JSONArray
import org.json.JSONObject

/**
 * Normalized screen positions of the lock-screen PIN keys, learned from a
 * successful node-based unlock. Coordinates are 0–1 so they survive density
 * changes; the display size and rotation are part of the cache key.
 *
 * This is only used when the keyguard stops exposing digit labels and the
 * operator explicitly opted in with "allow cached keypad". Blind tapping can
 * burn a failed unlock attempt, so it is never the default.
 */
data class KeypadLayout(
    val digits: Map<Char, PointF>,
    val enter: PointF?
)

object PinKeypadStore {
    private const val PREFS = "rc_pin_keypad"

    fun key(width: Int, height: Int, rotation: Int): String =
        "layout_${width}x${height}_$rotation"

    fun save(
        context: Context,
        width: Int,
        height: Int,
        rotation: Int,
        layout: KeypadLayout
    ) {
        if (width <= 0 || height <= 0 || layout.digits.size < 10) return
        val digits = JSONObject()
        layout.digits.forEach { (digit, point) ->
            digits.put(digit.toString(), JSONArray().put(point.x).put(point.y))
        }
        val json = JSONObject()
            .put("digits", digits)
            .put("enter", layout.enter?.let { JSONArray().put(it.x).put(it.y) })
        prefs(context).edit()
            .putString(key(width, height, rotation), json.toString())
            .apply()
    }

    fun load(
        context: Context,
        width: Int,
        height: Int,
        rotation: Int
    ): KeypadLayout? {
        val raw = prefs(context).getString(key(width, height, rotation), null)
            ?: return null
        return try {
            val json = JSONObject(raw)
            val digitsJson = json.optJSONObject("digits") ?: return null
            val digits = mutableMapOf<Char, PointF>()
            val keys = digitsJson.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                if (name.length != 1 || !name[0].isDigit()) continue
                val point = digitsJson.optJSONArray(name) ?: continue
                val x = point.optDouble(0, -1.0).toFloat()
                val y = point.optDouble(1, -1.0).toFloat()
                if (x in 0f..1f && y in 0f..1f) digits[name[0]] = PointF(x, y)
            }
            if (digits.size < 10) return null
            val enter = json.optJSONArray("enter")?.let {
                val x = it.optDouble(0, -1.0).toFloat()
                val y = it.optDouble(1, -1.0).toFloat()
                if (x in 0f..1f && y in 0f..1f) PointF(x, y) else null
            }
            KeypadLayout(digits, enter)
        } catch (_: Throwable) {
            null
        }
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
