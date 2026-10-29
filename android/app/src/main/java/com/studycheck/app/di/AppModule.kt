package com.studycheck.app.di

import android.content.Context
import androidx.room.Room
import com.studycheck.app.data.ApiService
import com.studycheck.app.data.AppDatabase
import com.studycheck.app.data.CheckinDao
import com.studycheck.app.data.EntryDao
import com.studycheck.app.data.PendingEntryDao
import com.studycheck.app.data.PendingDao
import com.studycheck.app.data.TokenStore
import com.studycheck.app.data.buildApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideTokenStore(@ApplicationContext context: Context): TokenStore = TokenStore(context)

    @Provides
    @Singleton
    fun provideApi(store: TokenStore): ApiService = buildApi(store)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "studycheck.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideCheckinDao(db: AppDatabase): CheckinDao = db.checkinDao()

    @Provides
    fun providePendingDao(db: AppDatabase): PendingDao = db.pendingDao()

    @Provides
    fun provideEntryDao(db: AppDatabase): EntryDao = db.entryDao()

    @Provides
    fun providePendingEntryDao(db: AppDatabase): PendingEntryDao = db.pendingEntryDao()
}
