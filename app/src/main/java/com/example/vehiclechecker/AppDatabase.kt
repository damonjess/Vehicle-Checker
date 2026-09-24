package com.example.vehiclechecker

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [VehicleEntity::class, NoteEntity::class, CachedVehicleEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun vehicleDao(): VehicleDao
    abstract fun noteDao(): NoteDao
    abstract fun cachedVehicleDao(): CachedVehicleDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `my_notes` " +
                        "(`registration` TEXT NOT NULL, `note` TEXT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, PRIMARY KEY(`registration`))"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cached_vehicles` " +
                        "(`registration` TEXT NOT NULL, `vehicleJson` TEXT NOT NULL, " +
                        "`motJson` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`registration`))"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `recent_searches` ADD COLUMN `isFavourite` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `recent_searches` ADD COLUMN `taxDueEpochMs` INTEGER")
                db.execSQL("ALTER TABLE `recent_searches` ADD COLUMN `motExpiryEpochMs` INTEGER")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vehicle_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
