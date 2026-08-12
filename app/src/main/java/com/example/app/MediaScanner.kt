package com.example.app

import android.content.Context

/**
 * ماسح الوسائط (MediaScanner) - يرث من GalleryBrowser.
 * هذه الفئة هي بديل media_scanner.py.
 */
class MediaScanner(
    context: Context,
    scanner: Any? = null,
    telegram: Any? = null
) : GalleryBrowser(context, scanner, telegram) {

    companion object {
        private const val TAG = "MediaScanner"

        /**
         * دالة المصنع (Factory Method) لإنشاء كائن جديد من MediaScanner.
         * تعادل الدالة create() في الملف الأصلي.
         *
         * @param context سياق التطبيق
         * @param scanner كائن الماسح الضوئي (اختياري)
         * @param telegram كائن Telegram (اختياري)
         * @return كائن MediaScanner جاهز للاستخدام
         */
        @JvmStatic
        fun create(
            context: Context,
            scanner: Any? = null,
            telegram: Any? = null
        ): MediaScanner {
            return MediaScanner(context, scanner, telegram)
        }
    }
}