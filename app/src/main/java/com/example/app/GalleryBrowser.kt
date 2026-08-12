package com.example.app

import android.content.Context

/**
 * متصفح المعرض (GalleryBrowser) - فئة أساسية لاستعراض وتصنيف الوسائط.
 * تم تعديلها لتكون open class للسماح بالوراثة من قبل MediaScanner.
 */
open class GalleryBrowser(
    context: Context,
    scanner: Any? = null,
    telegram: Any? = null
) {
    // يمكن إضافة خصائص وطرق هنا لاحقاً حسب الحاجة

    companion object {
        private const val TAG = "GalleryBrowser"

        /**
         * دالة المصنع (Factory Method) لإنشاء كائن جديد من GalleryBrowser.
         * تعادل الدالة create() في الملف الأصلي.
         *
         * @param context سياق التطبيق
         * @param scanner كائن الماسح الضوئي (اختياري)
         * @param telegram كائن Telegram (اختياري)
         * @return كائن GalleryBrowser جاهز للاستخدام
         */
        @JvmStatic
        fun create(
            context: Context,
            scanner: Any? = null,
            telegram: Any? = null
        ): GalleryBrowser {
            return GalleryBrowser(context, scanner, telegram)
        }
    }
}