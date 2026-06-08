package com.madhav.scanner.core.data.di

import android.content.Context
import androidx.room.Room
import com.madhav.scanner.core.data.AppDatabase
import com.madhav.scanner.core.data.dao.BenchRunDao
import com.madhav.scanner.core.data.dao.ScanDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DATABASE_NAME).build()

    @Provides
    fun provideScanDao(db: AppDatabase): ScanDao = db.scanDao()

    @Provides
    fun provideBenchRunDao(db: AppDatabase): BenchRunDao = db.benchRunDao()
}
