package com.robert.noteforge.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.robert.noteforge.data.AppDatabase
import com.robert.noteforge.data.AudioFileDao
import com.robert.noteforge.data.InstrumentDao
import com.robert.noteforge.data.ProjectSettingsDao
import com.robert.noteforge.data.ScoreNoteDao
import com.robert.noteforge.data.SongTrackConfigDao
import com.robert.noteforge.data.TrackDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `score_notes` ADD COLUMN `accidental` INTEGER NOT NULL DEFAULT 0")
        }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `tracks` ADD COLUMN `solo` INTEGER NOT NULL DEFAULT 0")
        }
    }

    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `tracks` ADD COLUMN `noteColorArgb` INTEGER")
        }
    }

    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `tracks` ADD COLUMN `volume` REAL NOT NULL DEFAULT 1.0")
            db.execSQL("ALTER TABLE `tracks` ADD COLUMN `clef` TEXT NOT NULL DEFAULT 'TREBLE'")
            db.execSQL("ALTER TABLE `tracks` ADD COLUMN `timeSigNum` INTEGER NOT NULL DEFAULT 4")
            db.execSQL("ALTER TABLE `tracks` ADD COLUMN `timeSigDen` INTEGER NOT NULL DEFAULT 4")
        }
    }

    // v6: time signature moves from per-track to the global project settings
    // (next to tempo). Tables are rebuilt without column defaults so the
    // on-device schema matches the entities exactly. Existing meters are
    // preserved by seeding the global signature from beatsPerMeasure.
    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `project_settings_new` (" +
                    "`id` INTEGER NOT NULL, `tempoBpm` INTEGER NOT NULL, " +
                    "`beatsPerMeasure` INTEGER NOT NULL, `snapDivision` INTEGER NOT NULL, " +
                    "`timeSigNum` INTEGER NOT NULL, `timeSigDen` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "INSERT INTO `project_settings_new` " +
                    "(`id`, `tempoBpm`, `beatsPerMeasure`, `snapDivision`, `timeSigNum`, `timeSigDen`) " +
                    "SELECT `id`, `tempoBpm`, `beatsPerMeasure`, `snapDivision`, `beatsPerMeasure`, 4 " +
                    "FROM `project_settings`"
            )
            db.execSQL("DROP TABLE `project_settings`")
            db.execSQL("ALTER TABLE `project_settings_new` RENAME TO `project_settings`")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `tracks_new` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`instrumentId` INTEGER NOT NULL, `name` TEXT NOT NULL, " +
                    "`orderIndex` INTEGER NOT NULL, `muted` INTEGER NOT NULL, " +
                    "`solo` INTEGER NOT NULL, `noteColorArgb` INTEGER, " +
                    "`volume` REAL NOT NULL, `clef` TEXT NOT NULL)"
            )
            db.execSQL(
                "INSERT INTO `tracks_new` (`id`, `instrumentId`, `name`, `orderIndex`, " +
                    "`muted`, `solo`, `noteColorArgb`, `volume`, `clef`) " +
                    "SELECT `id`, `instrumentId`, `name`, `orderIndex`, " +
                    "`muted`, `solo`, `noteColorArgb`, `volume`, `clef` FROM `tracks`"
            )
            db.execSQL("DROP TABLE `tracks`")
            db.execSQL("ALTER TABLE `tracks_new` RENAME TO `tracks`")
        }
    }

    // v7: per-instrument loop region (previously only a dead loopEnabled flag)
    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `instruments` ADD COLUMN `loopStartMs` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `instruments` ADD COLUMN `loopEndMs` INTEGER NOT NULL DEFAULT -1")
        }
    }

    // v8: synth instruments — a live WildPatch-style patch stored as JSON
    // (null = classic sample instrument)
    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `instruments` ADD COLUMN `synthPatchJson` TEXT")
        }
    }

    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `instruments` ADD COLUMN `syncUid` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `instruments` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
            // Backfill stable UUIDs and timestamps for existing instruments.
            val now = System.currentTimeMillis()
            val c = db.query("SELECT `id` FROM `instruments`")
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val uid = java.util.UUID.randomUUID().toString()
                db.execSQL(
                    "UPDATE `instruments` SET `syncUid` = ?, `updatedAt` = ? WHERE `id` = ?",
                    arrayOf(uid, now, id)
                )
            }
            c.close()
        }
    }

    private val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `project_settings` ADD COLUMN `beamQuantizeGrid` REAL NOT NULL DEFAULT 0.25")
            db.execSQL("ALTER TABLE `project_settings` ADD COLUMN `beamIoiThreshold` REAL NOT NULL DEFAULT 0.75")
            db.execSQL("ALTER TABLE `project_settings` ADD COLUMN `beamGapThreshold` REAL NOT NULL DEFAULT 0.5")
        }
    }

    private val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `song_track_configs` (
                    `songKey` TEXT NOT NULL,
                    `trackIndex` INTEGER NOT NULL,
                    `instrumentId` INTEGER NOT NULL,
                    `volume` REAL NOT NULL,
                    `muted` INTEGER NOT NULL,
                    `solo` INTEGER NOT NULL,
                    `clef` TEXT NOT NULL,
                    `noteColorArgb` INTEGER,
                    PRIMARY KEY(`songKey`, `trackIndex`)
                )"""
            )
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "noteforge.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
            .build()

    @Provides fun provideAudioFileDao(db: AppDatabase): AudioFileDao = db.audioFileDao()
    @Provides fun provideInstrumentDao(db: AppDatabase): InstrumentDao = db.instrumentDao()
    @Provides fun provideTrackDao(db: AppDatabase): TrackDao = db.trackDao()
    @Provides fun provideScoreNoteDao(db: AppDatabase): ScoreNoteDao = db.scoreNoteDao()
    @Provides fun provideProjectSettingsDao(db: AppDatabase): ProjectSettingsDao = db.projectSettingsDao()
    @Provides fun provideSongTrackConfigDao(db: AppDatabase): SongTrackConfigDao = db.songTrackConfigDao()
}
