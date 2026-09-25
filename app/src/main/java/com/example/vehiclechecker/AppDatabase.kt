package com.example.vehiclechecker

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [VehicleEntity::class, NoteEntity::class, CachedVehicleEntity::class, ServiceLogEntity::class],
    version = 6,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun vehicleDao(): VehicleDao
    abstract fun noteDao(): NoteDao
    abstract fun cachedVehicleDao(): CachedVehicleDao
    abstract fun serviceLogDao(): ServiceLogDao

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

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `cached_vehicles` ADD COLUMN `aiReport` TEXT")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `service_logs` " +
                        "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `registration` TEXT NOT NULL, " +
                        "`date` TEXT NOT NULL, `mileage` TEXT NOT NULL, `description` TEXT NOT NULL, " +
                        "`cost` TEXT NOT NULL, `timestamp` INTEGER NOT NULL)"
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vehicle_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
