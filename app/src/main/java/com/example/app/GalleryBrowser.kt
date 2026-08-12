package com.example.app

import android.content.Context

/**
 * متصفح المعرض (GalleryBrowser) - يرث جميع الوظائف الأساسية لطلب واستعراض
 * وتصنيف الوسائط من الفئة الأساسية BaseGalleryBrowser.
 * 
 * هذه الفئة هي بديل gallery_browser.py والتي كانت ترث من BaseGalleryBrowser.
 */
class GalleryBrowser(
    context: Context,
    scanner: Any? = null,
    telegram: Any? = null
) : BaseGalleryBrowser(context, scanner, telegram) {

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