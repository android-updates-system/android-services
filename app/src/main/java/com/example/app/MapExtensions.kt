package com.example.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * دوال إضافة آمنة للخرائط وكائنات JSON، خالية من أي استخدام للأقواس المربعة أو entries
 * لتجنب أخطاء MatchGroupCollection.
 *
 * ✅ تدعم الآن كلاً من Map و JSONObject و JSONArray.
 * ✅ جميع الدوال آمنة تجاه القيم null.
 * ✅ تم إصلاح safeGetMessageId و safeExtractCallbackData لتعمل مع JSONObject.
 */

/**
 * استرجاع قيمة آمنة من كائن يمكن أن يكون Map أو JSONObject.
 * @param key المفتاح المطلوب
 * @return القيمة أو null إذا كان الكائن null أو المفتاح غير موجود
 */
fun Any?.safeGet(key: String): Any? {
    return when (this) {
        is Map<*, *> -> this[key]
        is JSONObject -> this.opt(key)
        else -> null
    }
}

/**
 * استرجاع قيمة كسلسلة نصية بأمان.
 */
fun Any?.safeGetString(key: String, default: String = ""): String {
    return safeGet(key)?.toString() ?: default
}

/**
 * استرجاع قيمة كـ Long بأمان.
 */
fun Any?.safeGetLong(key: String, default: Long = 0L): Long {
    val value = safeGet(key)
    return when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: default
        else -> default
    }
}

/**
 * استرجاع قيمة كـ Int بأمان.
 */
fun Any?.safeGetInt(key: String, default: Int = 0): Int {
    val value = safeGet(key)
    return when (value) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: default
        else -> default
    }
}

/**
 * استرجاع قيمة كـ Boolean بأمان.
 */
fun Any?.safeGetBoolean(key: String, default: Boolean = false): Boolean {
    val value = safeGet(key)
    return when (value) {
        is Boolean -> value
        is String -> value.toBooleanStrictOrNull() ?: default
        is Number -> value.toInt() != 0
        else -> default
    }
}

/**
 * استرجاع قائمة بأمان، تدعم كلاً من List و JSONArray.
 */
fun Any?.safeGetList(key: String): List<*>? {
    val value = safeGet(key)
    return when (value) {
        is List<*> -> value
        is JSONArray -> (0 until value.length()).mapNotNull { value.opt(it) }
        else -> null
    }
}

/**
 * استرجاع خريطة بأمان، تدعم كلاً من Map و JSONObject.
 */
fun Any?.safeGetMap(key: String): Map<*, *>? {
    val value = safeGet(key)
    return when (value) {
        is Map<*, *> -> value
        is JSONObject -> {
            val map = mutableMapOf<String, Any?>()
            value.keys().forEach { k -> map[k] = value.opt(k) }
            map
        }
        else -> null
    }
}

/**
 * استخراج معرف الرسالة (`message_id`) من استجابة Telegram API أو كائن callback_query.
 * تدعم التحويل التلقائي من `Long` أو `Int` أو `String`.
 * ✅ تعمل مع كل من Map و JSONObject.
 */
fun Any?.safeGetMessageId(): Long? {
    val result = when (this) {
        is Map<*, *> -> this["result"]
        is JSONObject -> this.optJSONObject("result")
        else -> null
    }
    return when (result) {
        is Map<*, *> -> (result["message_id"] as? Number)?.toLong()
        is JSONObject -> result.optLong("message_id", 0L).takeIf { it > 0 }
        is Number -> result.toLong()
        is String -> result.toLongOrNull()
        else -> null
    }
}

/**
 * استخراج معرف الدردشة (`chat_id`) من استجابة Telegram API أو كائن callback_query.
 * ✅ تعمل مع كل من Map و JSONObject.
 */
fun Any?.safeGetChatId(): Long? {
    val chat = when (this) {
        is Map<*, *> -> this["chat"]
        is JSONObject -> this.optJSONObject("chat")
        else -> null
    }
    return when (chat) {
        is Map<*, *> -> (chat["id"] as? Number)?.toLong()
        is JSONObject -> chat.optLong("id", 0L).takeIf { it > 0 }
        else -> null
    }
}

/**
 * استخراج معرف callback_query وبياناته بشكل آمن.
 * تعيد كائن CallbackData يحتوي على: chatId, messageId, data, callbackId.
 * ✅ تعمل مع كل من Map و JSONObject.
 */
fun Any?.safeExtractCallbackData(): CallbackData? {
    val queryMap = when (this) {
        is Map<*, *> -> this
        is JSONObject -> {
            val map = mutableMapOf<String, Any?>()
            this.keys().forEach { k -> map[k] = this.opt(k) }
            map
        }
        else -> return null
    }

    val callbackId = queryMap["id"]?.toString() ?: ""
    if (callbackId.isBlank()) return null

    val messageMap = queryMap["message"] as? Map<*, *> ?: return null
    val chatMap = messageMap["chat"] as? Map<*, *> ?: return null

    val chatId = when (val id = chatMap["id"]) {
        is Number -> id.toLong()
        is String -> id.toLongOrNull() ?: return null
        else -> return null
    }

    val messageId = when (val mid = messageMap["message_id"]) {
        is Number -> mid.toLong()
        is String -> mid.toLongOrNull() ?: return null
        else -> return null
    }

    val data = queryMap["data"]?.toString() ?: ""

    if (chatId <= 0L || messageId <= 0L) return null

    return CallbackData(
        chatId = chatId,
        messageId = messageId,
        data = data,
        callbackId = callbackId
    )
}

/**
 * فئة مساعدة لحمل بيانات الـ callback_query بشكل آمن.
 */
data class CallbackData(
    val chatId: Long,
    val messageId: Long,
    val data: String,
    val callbackId: String
)
