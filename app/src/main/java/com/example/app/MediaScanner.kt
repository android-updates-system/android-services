package com.example.app

import android.content.Context

/**
 * ماسح الوسائط (MediaScanner) - يرث من GalleryBrowser.
 * هذه الفئة هي بديل media_scanner.py.
 * تم تعديل الوراثة من BaseGalleryBrowser إلى GalleryBrowser لحل خطأ Unresolved reference.
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