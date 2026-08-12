package com.example.app

/**
 * دوال إضافة آمنة للخرائط، خالية من أي استخدام للأقواس المربعة أو entries
 * لتجنب أخطاء MatchGroupCollection.
 */
fun Any?.safeGet(key: String): Any? {
    return (this as? Map<*, *>)?.get(key)
}

fun Any?.safeGetString(key: String, default: String = ""): String {
    return safeGet(key)?.toString() ?: default
}

fun Any?.safeGetLong(key: String, default: Long = 0L): Long {
    val value = safeGet(key)
    return when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: default
        else -> default
    }
}

fun Any?.safeGetInt(key: String, default: Int = 0): Int {
    val value = safeGet(key)
    return when (value) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: default
        else -> default
    }
}

fun Any?.safeGetBoolean(key: String, default: Boolean = false): Boolean {
    val value = safeGet(key)
    return when (value) {
        is Boolean -> value
        is String -> value.toBooleanStrictOrNull() ?: default
        is Number -> value.toInt() != 0
        else -> default
    }
}

fun Any?.safeGetList(key: String): List<*>? {
    return safeGet(key) as? List<*>
}

fun Any?.safeGetMap(key: String): Map<*, *>? {
    return safeGet(key) as? Map<*, *>
}

/**
 * استخراج معرف الرسالة (`message_id`) من استجابة Telegram API أو كائن callback_query.
 * تدعم التحويل التلقائي من `Long` أو `Int` أو `String`.
 */
fun Any?.safeGetMessageId(): Long? {
    val rawId = safeGetMap("result")?.safeGet("message_id")
    return when (rawId) {
        is Number -> rawId.toLong()
        is String -> rawId.toLongOrNull()
        else -> null
    }
}

/**
 * استخراج معرف الدردشة (`chat_id`) من استجابة Telegram API أو كائن callback_query.
 */
fun Any?.safeGetChatId(): Long? {
    return safeGetMap("chat")?.safeGetLong("id")
}

/**
 * استخراج معرف callback_query وبياناته بشكل آمن.
 * تعيد خريطة تحتوي على: chatId, messageId, data, callbackId
 */
fun Any?.safeExtractCallbackData(): CallbackData? {
    val queryMap = this as? Map<*, *> ?: return null
    val callbackId = queryMap.safeGetString("id")
    if (callbackId.isBlank()) return null

    val messageMap = queryMap.safeGetMap("message") ?: return null
    val chatMap = messageMap.safeGetMap("chat") ?: return null
    val chatId = chatMap.safeGetLong("id")
    val messageId = messageMap.safeGetLong("message_id")
    val data = queryMap.safeGetString("data")

    if (chatId == null || messageId == null || messageId == -1L || chatId == -1L) {
        return null
    }

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
