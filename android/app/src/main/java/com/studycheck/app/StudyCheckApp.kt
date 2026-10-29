package com.studycheck.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.studycheck.app.data.AuthInterceptor
import com.studycheck.app.data.TokenStore
import com.studycheck.app.work.ReminderScheduler
import com.studycheck.app.work.ReminderWorker
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class StudyCheckApp : Application(), ImageLoaderFactory {

    @Inject lateinit var tokenStore: TokenStore

    override fun onCreate() {
        super.onCreate()
        TokenStore.DEFAULT_BASE_URL = BuildConfig.DEFAULT_BASE_URL
        ReminderWorker.ensureChannel(this)
        ReminderScheduler.schedule(this, tokenStore.reminderHour, tokenStore.reminderEnabled)
    }

    /** Coil 加载私有照片：自动带上 JWT 头 */
    override fun newImageLoader(): ImageLoader {
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenStore))
            .build()
        return ImageLoader.Builder(this)
            .okHttpClient(client)
            .crossfade(true)
            .build()
    }
}
