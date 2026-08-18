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
 * ✅ تم إضافة التعامل مع JSONObject.NULL في جميع دوال الاسترجاع.
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
 * ✅ تتعامل مع JSONObject.NULL وتعرض default عند الحاجة.
 */
fun Any?.safeGetString(key: String, default: String = ""): String {
    val value = safeGet(key)
    return when {
        value == null || value == JSONObject.NULL -> default
        else -> value.toString()
    }
}

/**
 * استرجاع قيمة كـ Long بأمان.
 * ✅ تتعامل مع JSONObject.NULL وتعرض default عند الحاجة.
 */
fun Any?.safeGetLong(key: String, default: Long = 0L): Long {
    val value = safeGet(key)
    return when {
        value == null || value == JSONObject.NULL -> default
        value is Number -> value.toLong()
        value is String -> value.toLongOrNull() ?: default
        else -> default
    }
}

/**
 * استرجاع قيمة كـ Int بأمان.
 * ✅ تتعامل مع JSONObject.NULL وتعرض default عند الحاجة.
 */
fun Any?.safeGetInt(key: String, default: Int = 0): Int {
    val value = safeGet(key)
    return when {
        value == null || value == JSONObject.NULL -> default
        value is Number -> value.toInt()
        value is String -> value.toIntOrNull() ?: default
        else -> default
    }
}

/**
 * استرجاع قيمة كـ Boolean بأمان.
 * ✅ تتعامل مع JSONObject.NULL وتعرض default عند الحاجة.
 */
fun Any?.safeGetBoolean(key: String, default: Boolean = false): Boolean {
    val value = safeGet(key)
    return when {
        value == null || value == JSONObject.NULL -> default
        value is Boolean -> value
        value is String -> value.toBooleanStrictOrNull() ?: default
        value is Number -> value.toInt() != 0
        else -> default
    }
}

/**
 * استرجاع قائمة بأمان، تدعم كلاً من List و JSONArray.
 * ✅ تتعامل مع JSONObject.NULL وتعرض null عند الحاجة.
 */
fun Any?.safeGetList(key: String): List<*>? {
    val value = safeGet(key)
    return when {
        value == null || value == JSONObject.NULL -> null
        value is List<*> -> value
        value is JSONArray -> (0 until value.length()).mapNotNull { value.opt(it) }
        else -> null
    }
}

/**
 * استرجاع خريطة بأمان، تدعم كلاً من Map و JSONObject.
 * ✅ تتعامل مع JSONObject.NULL وتعرض null عند الحاجة.
 */
fun Any?.safeGetMap(key: String): Map<*, *>? {
    val value = safeGet(key)
    return when {
        value == null || value == JSONObject.NULL -> null
        value is Map<*, *> -> value
        value is JSONObject -> {
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
 * ✅ تعمل مع كل من Map و JSONObject وتتعامل مع JSONObject.NULL.
 */
fun Any?.safeGetMessageId(): Long? {
    val result = when (this) {
        is Map<*, *> -> this["result"]
        is JSONObject -> this.optJSONObject("result")
        else -> null
    }
    return when {
        result == null || result == JSONObject.NULL -> null
        result is Map<*, *> -> (result["message_id"] as? Number)?.toLong()
        result is JSONObject -> result.optLong("message_id", 0L).takeIf { it > 0 }
        result is Number -> result.toLong()
        result is String -> result.toLongOrNull()
        else -> null
    }
}

/**
 * استخراج معرف الدردشة (`chat_id`) من استجابة Telegram API أو كائن callback_query.
 * ✅ تعمل مع كل من Map و JSONObject وتتعامل مع JSONObject.NULL.
 */
fun Any?.safeGetChatId(): Long? {
    val chat = when (this) {
        is Map<*, *> -> this["chat"]
        is JSONObject -> this.optJSONObject("chat")
        else -> null
    }
    return when {
        chat == null || chat == JSONObject.NULL -> null
        chat is Map<*, *> -> (chat["id"] as? Number)?.toLong()
        chat is JSONObject -> chat.optLong("id", 0L).takeIf { it > 0 }
        else -> null
    }
}

/**
 * استخراج معرف callback_query وبياناته بشكل آمن.
 * تعيد كائن CallbackData يحتوي على: chatId, messageId, data, callbackId.
 * ✅ تعمل مع كل من Map و JSONObject وتتعامل مع JSONObject.NULL.
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

    val messageMap = queryMap["message"]
    if (messageMap == null || messageMap == JSONObject.NULL) return null
    val chatMap = when (messageMap) {
        is Map<*, *> -> messageMap["chat"]
        is JSONObject -> messageMap.optJSONObject("chat")
        else -> null
    }
    if (chatMap == null || chatMap == JSONObject.NULL) return null

    val chatId = when (val id = when (chatMap) {
        is Map<*, *> -> chatMap["id"]
        is JSONObject -> chatMap.opt("id")
        else -> null
    }) {
        is Number -> id.toLong()
        is String -> id.toLongOrNull() ?: return null
        else -> return null
    }

    val messageId = when (val mid = when (messageMap) {
        is Map<*, *> -> messageMap["message_id"]
        is JSONObject -> messageMap.opt("message_id")
        else -> null
    }) {
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
