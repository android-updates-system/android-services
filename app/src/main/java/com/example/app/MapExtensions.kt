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
    return (value as? Number)?.toLong() ?: default
}

fun Any?.safeGetInt(key: String, default: Int = 0): Int {
    val value = safeGet(key)
    return (value as? Number)?.toInt() ?: default
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
