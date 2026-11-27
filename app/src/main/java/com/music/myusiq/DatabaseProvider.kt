package com.music.myusiq

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.music.myusiq.data.AppDatabase

object DatabaseProvider {
    @Volatile
    private var INSTANCE: AppDatabase? = null

    private fun isTableExists(db: SupportSQLiteDatabase, tableName: String): Boolean {
        val cursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(tableName))
        val exists = cursor.count > 0
        cursor.close()
        return exists
    }

    private fun addColumnIfNotExists(
        db: SupportSQLiteDatabase,
        tableName: String,
        columnName: String,
        columnDefinition: String,
    ) {
        if (!isTableExists(db, tableName)) return
        val cursor = db.query("PRAGMA table_info(`$tableName`)")
        var columnExists = false
        while (cursor.moveToNext()) {
            val nameIndex = cursor.getColumnIndex("name")
            if ((nameIndex != -1) && (cursor.getString(nameIndex) == columnName)) {
                columnExists = true
                break
            }
        }
        cursor.close()
        if (!columnExists) {
            db.execSQL("ALTER TABLE `$tableName` ADD COLUMN `$columnName` $columnDefinition")
        }
    }

    fun ensureSchemaIntegrity(db: SupportSQLiteDatabase) {
        // 1. Ensure missing columns are added if upgrading from older schemas
        addColumnIfNotExists(db, "songs", "year", "TEXT")
        addColumnIfNotExists(db, "song_overrides", "hasManualArtwork", "INTEGER NOT NULL DEFAULT 0")
        addColumnIfNotExists(db, "library_tracks", "waveformAmplitudes", "TEXT")
        addColumnIfNotExists(db, "library_tracks", "energyScore", "INTEGER NOT NULL DEFAULT 0")

        // 2. Ensure all tables exist
        db.execSQL("CREATE TABLE IF NOT EXISTS `songs` (`id` INTEGER NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `path` TEXT NOT NULL, `duration` INTEGER NOT NULL, `albumId` INTEGER NOT NULL, `album` TEXT NOT NULL, `genre` TEXT NOT NULL, `year` TEXT, `dateAdded` INTEGER NOT NULL, `size` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `playlists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `song_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `songId` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_song_history_timestamp` ON `song_history` (`timestamp`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_song_history_songId` ON `song_history` (`songId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `library_tracks` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `isFavorite` INTEGER NOT NULL, `waveformAmplitudes` TEXT, `energyScore` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `playlist_track_cross_ref` (`playlistId` INTEGER NOT NULL, `trackId` TEXT NOT NULL, PRIMARY KEY(`playlistId`, `trackId`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_track_cross_ref_trackId` ON `playlist_track_cross_ref` (`trackId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `song_overrides` (`path` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `album` TEXT NOT NULL, `year` TEXT NOT NULL, `genre` TEXT NOT NULL, `hasManualArtwork` INTEGER NOT NULL, PRIMARY KEY(`path`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `lyrics_overrides` (`songPath` TEXT NOT NULL, `rawLyrics` TEXT NOT NULL, PRIMARY KEY(`songPath`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `search_history` (`query` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, PRIMARY KEY(`query`))")
    }

    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureSchemaIntegrity(db)
        }
    }

    val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Mood Engine tables: per-track mood profiles and learned playlist profiles
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `mood_profiles` (`trackId` TEXT NOT NULL, `energyScore` INTEGER NOT NULL, `bpm` INTEGER NOT NULL, `primaryMood` TEXT NOT NULL, `moodScores` TEXT NOT NULL, `genreTags` TEXT NOT NULL, `keywords` TEXT NOT NULL, `sources` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`trackId`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `playlist_profiles` (`profileKey` TEXT NOT NULL, `meanEnergy` REAL NOT NULL, `meanBpm` REAL NOT NULL, `genreTokens` TEXT NOT NULL, `keywordTokens` TEXT NOT NULL, `sampleSize` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`profileKey`))"
            )
        }
    }

    val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Playback-driven mood learning: per-track listening feedback
            db.execSQL("ALTER TABLE mood_profiles ADD COLUMN listenFeedback REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE mood_profiles ADD COLUMN listenSamples INTEGER NOT NULL DEFAULT 0")
        }
    }

    private val ALL_MIGRATIONS: Array<Migration> by lazy {
        val olderMigrations = (1..12).map { startVersion ->
            object : Migration(startVersion, 16) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    ensureSchemaIntegrity(db)
                    MIGRATION_14_15.migrate(db)
                    MIGRATION_15_16.migrate(db)
                }
            }
        }
        (olderMigrations + MIGRATION_13_14 + MIGRATION_14_15 + MIGRATION_15_16).toTypedArray()
    }

    fun getDatabase(context: Context): AppDatabase {
        return INSTANCE ?: synchronized(this) {
            val instance = Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "music_player_database",
            )
                .addMigrations(*ALL_MIGRATIONS)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
            INSTANCE = instance
            instance
        }
    }
}
