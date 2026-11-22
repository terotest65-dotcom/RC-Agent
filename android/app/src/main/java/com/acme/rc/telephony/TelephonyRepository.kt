package com.acme.rc.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads SIM/line details and inbox messages, sends SMS, and runs USSD codes
 * for call forwarding. Everything is best-effort: carrier provisioning,
 * permissions, and emulators all change what is available.
 */
object TelephonyRepository {

    fun telephonyJson(context: Context): JSONObject {
        val permissions = permissionsJson(context)
        val result = JSONObject().put("permissions", permissions)

        if (!permissions.optBoolean("readPhoneState") &&
            !permissions.optBoolean("readPhoneNumbers")
        ) {
            return result
                .put("sims", JSONArray())
                .put("error", "Phone permissions are off. Grant READ_PHONE_STATE and READ_PHONE_NUMBERS.")
        }

        val sims = JSONArray()
        try {
            val manager = context.getSystemService(SubscriptionManager::class.java)
            val telephony = context.getSystemService(TelephonyManager::class.java)
            val subscriptions = manager?.activeSubscriptionInfoList ?: emptyList()
            for (info in subscriptions) {
                sims.put(simJson(context, manager, telephony, info))
            }
            result.put("sims", sims)
            if (subscriptions.isEmpty()) {
                result.put("error", "No active SIM subscriptions on this device.")
            }
        } catch (t: Throwable) {
            result
                .put("sims", sims)
                .put("error", t.message ?: t.javaClass.simpleName)
        }
        return result
    }

    fun smsJson(context: Context, limit: Int): JSONObject {
        val result = JSONObject()
        if (!has(context, Manifest.permission.READ_SMS)) {
            return result
                .put("messages", JSONArray())
                .put("error", "READ_SMS is off. Grant it to list messages.")
        }

        val messages = JSONArray()
        val slotBySub = slotMap(context)
        val safeLimit = limit.coerceIn(1, 200)
        try {
            val projection = arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.READ,
                Telephony.Sms.TYPE,
                "sub_id"
            )
            val cursor = context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                projection,
                null,
                null,
                "${Telephony.Sms.DATE} DESC LIMIT $safeLimit"
            )
            cursor?.use { rows ->
                val idCol = rows.getColumnIndex(Telephony.Sms._ID)
                val addressCol = rows.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyCol = rows.getColumnIndex(Telephony.Sms.BODY)
                val dateCol = rows.getColumnIndex(Telephony.Sms.DATE)
                val readCol = rows.getColumnIndex(Telephony.Sms.READ)
                val typeCol = rows.getColumnIndex(Telephony.Sms.TYPE)
                val subCol = rows.getColumnIndex("sub_id")
                while (rows.moveToNext()) {
                    val subId = if (subCol >= 0 && !rows.isNull(subCol)) rows.getInt(subCol) else null
                    messages.put(
                        JSONObject()
                            .put("id", if (idCol >= 0) rows.getString(idCol) else "")
                            .put("address", if (addressCol >= 0) rows.getString(addressCol) ?: "" else "")
                            .put("body", if (bodyCol >= 0) rows.getString(bodyCol) ?: "" else "")
                            .put("date", if (dateCol >= 0) rows.getLong(dateCol) else 0L)
                            .put("read", readCol >= 0 && rows.getInt(readCol) != 0)
                            .put("type", if (typeCol >= 0) rows.getInt(typeCol) else 1)
                            .put("subId", subId ?: JSONObject.NULL)
                            .put(
                                "slotIndex",
                                subId?.let { slotBySub[it] } ?: JSONObject.NULL
                            )
                    )
                }
            }
            result.put("messages", messages)
        } catch (t: Throwable) {
            result
                .put("messages", messages)
                .put("error", t.message ?: t.javaClass.simpleName)
        }
        return result
    }

    fun sendSms(context: Context, to: String, body: String, subId: Int): String {
        if (to.isBlank()) throw IllegalArgumentException("Recipient is required")
        if (body.isBlank()) throw IllegalArgumentException("Message body is required")
        if (!has(context, Manifest.permission.SEND_SMS)) {
            throw SecurityException("SEND_SMS is not granted")
        }

        val manager = if (subId >= 0) {
            SmsManager.getSmsManagerForSubscriptionId(subId)
        } else {
            context.getSystemService(SmsManager::class.java)
        } ?: throw IllegalStateException("SMS manager is unavailable on this device")

        val parts = manager.divideMessage(body)
        manager.sendMultipartTextMessage(to.trim(), null, parts, null, null)
        val line = if (subId >= 0) "subscription $subId" else "the default line"
        return "Queued ${parts.size} part${if (parts.size == 1) "" else "s"} via $line"
    }

    fun sendUssd(
        context: Context,
        code: String,
        subId: Int,
        onResult: (ok: Boolean, detail: String) -> Unit
    ) {
        if (code.isBlank()) throw IllegalArgumentException("USSD code is required")
        if (!has(context, Manifest.permission.CALL_PHONE)) {
            throw SecurityException("CALL_PHONE is not granted")
        }

        val telephony = context.getSystemService(TelephonyManager::class.java)
            ?: throw IllegalStateException("Telephony is unavailable on this device")
        val target = if (subId >= 0) telephony.createForSubscriptionId(subId) else telephony

        target.sendUssdRequest(
            code.trim(),
            object : TelephonyManager.UssdResponseCallback() {
                override fun onReceiveUssdResponse(
                    telephonyManager: TelephonyManager?,
                    request: String?,
                    response: CharSequence?
                ) {
                    onResult(true, response?.toString().orEmpty().ifBlank { "Request accepted" })
                }

                override fun onReceiveUssdResponseFailed(
                    telephonyManager: TelephonyManager?,
                    request: String?,
                    failureCode: Int
                ) {
                    onResult(false, "USSD failed with code $failureCode")
                }
            },
            Handler(Looper.getMainLooper())
        )
    }

    private fun simJson(
        context: Context,
        manager: SubscriptionManager?,
        telephony: TelephonyManager?,
        info: SubscriptionInfo
    ): JSONObject {
        val subId = info.subscriptionId
        val line = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager?.getPhoneNumber(subId)
            } else {
                @Suppress("DEPRECATION")
                info.number
            }
        } catch (_: Throwable) {
            null
        }
        val lineTelephony = try {
            telephony?.createForSubscriptionId(subId)
        } catch (_: Throwable) {
            telephony
        }
        val signal = try {
            lineTelephony?.signalStrength
        } catch (_: Throwable) {
            null
        }
        val signalDbm = signal?.cellSignalStrengths
            ?.firstOrNull { it.dbm != Int.MAX_VALUE }
            ?.dbm
        val serial = try {
            lineTelephony?.simSerialNumber
        } catch (_: Throwable) {
            null
        }

        return JSONObject()
            .put("subId", subId)
            .put("slotIndex", info.simSlotIndex)
            .put("carrierName", info.carrierName?.toString() ?: JSONObject.NULL)
            .put("displayName", info.displayName?.toString() ?: JSONObject.NULL)
            .put("number", line ?: JSONObject.NULL)
            .put("countryIso", info.countryIso ?: JSONObject.NULL)
            .put("mcc", info.mccString ?: JSONObject.NULL)
            .put("mnc", info.mncString ?: JSONObject.NULL)
            .put("simState", simStateLabel(lineTelephony?.simState))
            .put("roaming", try { lineTelephony?.isNetworkRoaming ?: false } catch (_: Throwable) { false })
            .put("dataEnabled", try { lineTelephony?.isDataEnabled ?: false } catch (_: Throwable) { false })
            .put("serial", serial ?: JSONObject.NULL)
            .put("signalLevel", signal?.level ?: JSONObject.NULL)
            .put("signalDbm", signalDbm ?: JSONObject.NULL)
    }

    private fun simStateLabel(state: Int?): String = when (state) {
        TelephonyManager.SIM_STATE_READY -> "Ready"
        TelephonyManager.SIM_STATE_ABSENT -> "Absent"
        TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN required"
        TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK required"
        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "Network locked"
        TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "Card error"
        TelephonyManager.SIM_STATE_CARD_RESTRICTED -> "Restricted"
        TelephonyManager.SIM_STATE_NOT_READY -> "Not ready"
        TelephonyManager.SIM_STATE_PERM_DISABLED -> "Permanently disabled"
        null -> "Unknown"
        else -> "State $state"
    }

    private fun slotMap(context: Context): Map<Int, Int> {
        val slots = mutableMapOf<Int, Int>()
        try {
            val manager = context.getSystemService(SubscriptionManager::class.java)
            manager?.activeSubscriptionInfoList?.forEach {
                slots[it.subscriptionId] = it.simSlotIndex
            }
        } catch (_: Throwable) {
            // Permission or modem issue; leave the slot unset.
        }
        return slots
    }

    private fun permissionsJson(context: Context): JSONObject = JSONObject()
        .put("readSms", has(context, Manifest.permission.READ_SMS))
        .put("sendSms", has(context, Manifest.permission.SEND_SMS))
        .put("readPhoneState", has(context, Manifest.permission.READ_PHONE_STATE))
        .put("readPhoneNumbers", has(context, Manifest.permission.READ_PHONE_NUMBERS))
        .put("callPhone", has(context, Manifest.permission.CALL_PHONE))

    private fun has(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
