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
        db.execSQL("CREATE TABLE IF NOT EXISTS `song_overrides` (`path` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `album` TEXT NOT NULL, `year` TEXT NOT NULL, `genre` TEXT NOT NULL, `hasManualArtwork` INTEGER NOT NULL, PRIMARY KEY(`path`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `lyrics_overrides` (`songPath` TEXT NOT NULL, `rawLyrics` TEXT NOT NULL, PRIMARY KEY(`songPath`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `search_history` (`query` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, PRIMARY KEY(`query`))")
    }

    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureSchemaIntegrity(db)
        }
    }

    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureSchemaIntegrity(db)
        }
    }

    val MIGRATION_11_13 = object : Migration(11, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureSchemaIntegrity(db)
        }
    }

    private val ALL_MIGRATIONS: Array<Migration> by lazy {
        val olderMigrations = (1..10).map { startVersion ->
            object : Migration(startVersion, 13) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    ensureSchemaIntegrity(db)
                }
            }
        }
        (olderMigrations + MIGRATION_11_12 + MIGRATION_12_13 + MIGRATION_11_13).toTypedArray()
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
