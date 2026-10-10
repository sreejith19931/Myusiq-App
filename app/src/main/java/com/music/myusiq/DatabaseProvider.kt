package com.music.myusiq

import android.content.Context
import androidx.room.Room
import com.music.myusiq.data.AppDatabase

object DatabaseProvider {
    @Volatile
    private var INSTANCE: AppDatabase? = null

    fun getDatabase(context: Context): AppDatabase {
        return INSTANCE ?: synchronized(this) {
            val instance = Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "music_player_database",
            )
                .fallbackToDestructiveMigrationOnDowngrade(true)
                .build()
            INSTANCE = instance
            instance
        }
    }
}
