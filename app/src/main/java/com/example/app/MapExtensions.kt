package com.example.app

/**
 * مجموعة من دوال الإضافة (Extension Functions) للتعامل الآمن مع الخرائط
 * التي قد تكون من نوع `Any?`، لتجنب استخدام الأقواس المربعة `[]`
 * التي تسبب مشكلة MatchGroupCollection في مترجم Kotlin.
 */
fun Any?.safeGet(key: String): Any? {
    return (this as? Map<*, *>)?.entries?.firstOrNull { it.key?.toString() == key }?.value
}

/**
 * استخراج قيمة من الخريطة على شكل String، مع قيمة افتراضية في حال عدم الوجود.
 */
fun Any?.safeGetString(key: String, default: String = ""): String {
    return safeGet(key)?.toString() ?: default
}

/**
 * استخراج قيمة من الخريطة على شكل Long، مع قيمة افتراضية في حال عدم الوجود.
 */
fun Any?.safeGetLong(key: String, default: Long = 0L): Long {
    return (safeGet(key) as? Number)?.toLong() ?: default
}

/**
 * استخراج قيمة من الخريطة على شكل Int، مع قيمة افتراضية في حال عدم الوجود.
 */
fun Any?.safeGetInt(key: String, default: Int = 0): Int {
    return (safeGet(key) as? Number)?.toInt() ?: default
}

/**
 * استخراج قيمة من الخريطة على شكل Boolean، مع قيمة افتراضية في حال عدم الوجود.
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
 * استخراج قيمة من الخريطة على شكل List<*>، مع قيمة افتراضية في حال عدم الوجود.
 */
fun Any?.safeGetList(key: String): List<*>? {
    return safeGet(key) as? List<*>
}

/**
 * استخراج قيمة من الخريطة على شكل Map<*, *>، مع قيمة افتراضية في حال عدم الوجود.
 */
fun Any?.safeGetMap(key: String): Map<*, *>? {
    return safeGet(key) as? Map<*, *>
}

/**
 * دالة مساعدة لتحديد ما إذا كانت الخريطة تحتوي على مفتاح معين.
 */
fun Any?.safeContainsKey(key: String): Boolean {
    return (this as? Map<*, *>)?.keys?.any { it?.toString() == key } == true
}

/**
 * دالة مساعدة لاستخراج القيمة مع معالجة الأخطاء، وإرجاع Result.
 */
fun <T> Any?.safeGetOrElse(key: String, transform: (Any?) -> T, default: T): T {
    return try {
        val value = safeGet(key)
        if (value != null) transform(value) else default
    } catch (e: Exception) {
        default
    }
}