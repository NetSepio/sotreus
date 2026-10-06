package app.sotreus.core.database.di

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.room.Room
import app.sotreus.core.database.Migrations
import app.sotreus.core.database.SotreusDatabase
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
    fun provideDatabase(@ApplicationContext context: Context): SotreusDatabase {
        val builder = Room.databaseBuilder(context, SotreusDatabase::class.java, SotreusDatabase.NAME)
            .addMigrations(*Migrations.ALL)
        // Never destructive in release builds (handoff §23).
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            builder.fallbackToDestructiveMigration(dropAllTables = true)
        }
        return builder.build()
    }

    @Provides fun entityDao(db: SotreusDatabase) = db.entityDao()
    @Provides fun observationDao(db: SotreusDatabase) = db.observationDao()
    @Provides fun encounterDao(db: SotreusDatabase) = db.encounterDao()
    @Provides fun placeDao(db: SotreusDatabase) = db.placeDao()
    @Provides fun sessionDao(db: SotreusDatabase) = db.sessionDao()
    @Provides fun attentionDao(db: SotreusDatabase) = db.attentionDao()
    @Provides fun profileDao(db: SotreusDatabase) = db.profileDao()
    @Provides fun friendDao(db: SotreusDatabase) = db.friendDao()
    @Provides fun proofDao(db: SotreusDatabase) = db.proofDao()
    @Provides fun contextDao(db: SotreusDatabase) = db.contextDao()
    @Provides fun trackingDao(db: SotreusDatabase) = db.trackingDao()
}
