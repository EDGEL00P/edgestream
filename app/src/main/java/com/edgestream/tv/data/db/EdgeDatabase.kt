package com.edgestream.tv.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

class Converters {
    @TypeConverter fun sourceType(v: SourceType): String = v.name
    @TypeConverter fun sourceType(v: String): SourceType = SourceType.valueOf(v)
    @TypeConverter fun vodKind(v: VodKind): String = v.name
    @TypeConverter fun vodKind(v: String): VodKind = VodKind.valueOf(v)
}

data class GroupCount(val groupName: String, val count: Int)

@Dao
interface SourceDao {
    @Transaction
    @Query("SELECT * FROM sources ORDER BY id")
    fun observeAll(): Flow<List<SourceEntity>>

    @Transaction
    @Query("SELECT * FROM sources ORDER BY id")
    suspend fun all(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun get(id: Long): SourceEntity?

    @Insert
    suspend fun insert(source: SourceEntity): Long

    @Upsert
    suspend fun upsert(source: SourceEntity)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ChannelDao {
    @Transaction
    @Query("SELECT groupName, COUNT(*) AS count FROM channels GROUP BY groupName ORDER BY MIN(sourceId * 1000000 + number)")
    fun observeGroups(): Flow<List<GroupCount>>

    @Transaction
    @Query("SELECT * FROM channels ORDER BY sourceId, number")
    fun observeAll(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE `key` = :key")
    suspend fun byKey(key: String): ChannelEntity?

    @Transaction
    @Query("SELECT * FROM channels WHERE name LIKE '%' || :q || '%' OR groupName LIKE '%' || :q || '%' ORDER BY sourceId, number LIMIT 200")
    suspend fun search(q: String): List<ChannelEntity>

    @Transaction
    @Query("SELECT * FROM channels WHERE sourceId = :sourceId")
    suspend fun forSource(sourceId: Long): List<ChannelEntity>

    @Query("UPDATE channels SET epgId = :epgId WHERE id = :id")
    suspend fun setEpgId(id: Long, epgId: String?)

    /** One transaction, so the UI reloads once instead of once per channel. */
    @Transaction
    suspend fun setEpgIds(updates: List<Pair<Long, String?>>) { updates.forEach { (id, epg) -> setEpgId(id, epg) } }

    @Query("UPDATE channels SET logo = :logo WHERE id = :id")
    suspend fun setLogo(id: Long, logo: String?)

    /** One transaction, so the UI reloads once instead of once per channel. */
    @Transaction
    suspend fun setLogos(updates: List<Pair<Long, String?>>) { updates.forEach { (id, logo) -> setLogo(id, logo) } }

    @Query("UPDATE channels SET offline = :offline WHERE id IN (:ids)")
    suspend fun setOffline(ids: List<Long>, offline: Boolean)

    @Query("DELETE FROM channels WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(channels: List<ChannelEntity>)

    @Transaction
    @Query("SELECT * FROM channels")
    suspend fun all(): List<ChannelEntity>

    @Query("DELETE FROM channels WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)

    @Transaction
    @Query("SELECT `key`, epgId FROM channels WHERE sourceId = :sourceId AND epgId IS NOT NULL")
    suspend fun guideLinks(sourceId: Long): List<GuideLink>

    @Transaction
    suspend fun replaceForSource(sourceId: Long, channels: List<ChannelEntity>) {
        deleteForSource(sourceId)
        channels.chunked(500).forEach { insertAll(it) }
    }
}

data class GuideLink(val key: String, val epgId: String)

@Dao
interface VodDao {
    @Transaction
    @Query("SELECT DISTINCT category FROM vod")
    suspend fun categories(): List<String>

    @Query("DELETE FROM vod WHERE category IN (:categories)")
    suspend fun deleteCategories(categories: List<String>)

    @Transaction
    @Query("SELECT * FROM vod WHERE kind = :kind ORDER BY added DESC LIMIT :limit")
    fun observeRecent(kind: VodKind, limit: Int): Flow<List<VodEntity>>


    @Transaction
    @Query("SELECT * FROM vod WHERE kind = :kind AND category IN (:categories) ORDER BY added DESC, name")
    fun observeInCategories(kind: VodKind, categories: List<String>): Flow<List<VodEntity>>

    @Query("SELECT * FROM vod WHERE `key` = :key LIMIT 1")
    suspend fun byKey(key: String): VodEntity?

    @Query("SELECT * FROM vod WHERE `key` IN (:keys)")
    suspend fun byKeys(keys: List<String>): List<VodEntity>

    @Transaction
    @Query("SELECT * FROM vod WHERE name LIKE '%' || :q || '%' ORDER BY name LIMIT 200")
    suspend fun search(q: String): List<VodEntity>

    @Query("DELETE FROM vod WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<VodEntity>)

    @Transaction
    suspend fun replaceForSource(sourceId: Long, items: List<VodEntity>) {
        deleteForSource(sourceId)
        items.chunked(500).forEach { insertAll(it) }
    }
}

/** One guide entry, just enough to recognise a game in it. */
data class GuideLine(val epgId: String, val title: String, val subtitle: String?, val start: Long, val stop: Long)

@Dao
interface ProgrammeDao {
    /**
     * Matchup-looking programmes ("Broncos v Storm", "Bruins at Rangers", "A @ B") that overlap
     * [from]..[to], in one pass: the rest of the guide can't name a game.
     */
    @Query(
        "SELECT epgId, title, subtitle, start, stop FROM programmes WHERE stop > :from AND start < :to " +
            "AND ((title || ' ' || IFNULL(subtitle, '')) LIKE '% v %' OR (title || ' ' || IFNULL(subtitle, '')) LIKE '% vs%' " +
            "OR (title || ' ' || IFNULL(subtitle, '')) LIKE '% at %' OR (title || ' ' || IFNULL(subtitle, '')) LIKE '%@%')",
    )
    suspend fun matchupsBetween(from: Long, to: Long): List<GuideLine>

    @Transaction
    @Query("SELECT * FROM programmes WHERE epgId = :epgId AND stop > :from AND start < :to ORDER BY start")
    suspend fun forChannel(epgId: String, from: Long, to: Long): List<ProgrammeEntity>

    @Transaction
    @Query("SELECT * FROM programmes WHERE epgId IN (:epgIds) AND stop > :from AND start < :to ORDER BY start")
    suspend fun forChannels(epgIds: List<String>, from: Long, to: Long): List<ProgrammeEntity>

    /**
     * What is on now and next across every channel: at most two rows per guide channel. Loading
     * every programme in the window instead overflowed the cursor once guides held 200k+ rows.
     *
     * Written without window functions: `ROW_NUMBER()` needs SQLite 3.25, which only ships from
     * Android 11, while the app supports API 24. The (epgId, start) index makes both grouped
     * lookups cheap.
     */
    @Transaction
    @Query(
        """SELECT p.id, p.sourceId, p.epgId, p.start, p.stop, p.title, p.description, p.category,
                  p.subtitle, p.year, p.rating
           FROM programmes p
           JOIN (
               SELECT epgId AS e, MAX(start) AS s FROM programmes
               WHERE start <= :now AND stop > :now GROUP BY epgId
               UNION ALL
               SELECT epgId AS e, MIN(start) AS s FROM programmes
               WHERE start > :now AND start < :until GROUP BY epgId
           ) w ON p.epgId = w.e AND p.start = w.s
           ORDER BY p.start"""
    )
    suspend fun nowAndNext(now: Long, until: Long): List<ProgrammeEntity>

    @Query("SELECT COUNT(*) FROM programmes")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM programmes")
    suspend fun count(): Int

    /**
     * Hard ceiling for the whole guide. Keeps the earliest-starting [keep] rows, so catch-up
     * history and the near schedule survive while the far end of the horizon is dropped.
     */
    @Query("DELETE FROM programmes WHERE id NOT IN (SELECT id FROM programmes ORDER BY start ASC LIMIT :keep)")
    suspend fun trimTo(keep: Int)

    @Transaction
    @Query("SELECT * FROM programmes WHERE title LIKE '%' || :q || '%' AND stop > :now ORDER BY start LIMIT 100")
    suspend fun search(q: String, now: Long): List<ProgrammeEntity>

    @Query("DELETE FROM programmes WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)

    @Query("DELETE FROM programmes WHERE stop < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM programmes WHERE start > :after")
    suspend fun deleteStartingAfter(after: Long)

    /**
     * Rows older than the catch-up window, matched on start rather than stop. Old builds stored
     * "No event scheduled" rows starting a year ago with a stop clamped to the new horizon, so a
     * stop-based prune would keep them alive for days.
     */
    @Query("DELETE FROM programmes WHERE start < :before")
    suspend fun deleteStartedBefore(before: Long)

    /** Legacy rows can carry a stop past the storage horizon; trim it without dropping the row. */
    @Query("UPDATE programmes SET stop = :at WHERE stop > :at")
    suspend fun clampStop(at: Long)

    /**
     * Rows for a guide id no channel points at any more. An older build wrote placeholder
     * listings under "name:6:L:<stream>" ids; after the id format changed those rows could never
     * be reached again, but they still counted towards the guide ceiling.
     */
    @Query("DELETE FROM programmes WHERE epgId NOT IN (SELECT epgId FROM channels WHERE epgId IS NOT NULL)")
    suspend fun deleteOrphaned()

    /** Ended rows are only kept where the channel offers catch-up. */
    @Query(
        """DELETE FROM programmes WHERE stop < :before AND epgId NOT IN (
            SELECT epgId FROM channels WHERE catchupDays > 0 AND epgId IS NOT NULL)"""
    )
    suspend fun deleteEnded(before: Long)

    /** Replaces one guide channel's schedule without touching the rest of the source's guide. */
    @Query("DELETE FROM programmes WHERE sourceId = :sourceId AND epgId IN (:epgIds)")
    suspend fun deleteFor(sourceId: Long, epgIds: List<String>)

    /** Programmes left behind by a source that has since been removed. */
    @Query("DELETE FROM programmes WHERE sourceId NOT IN (SELECT id FROM sources)")
    suspend fun deleteOrphans()

    @Query("DELETE FROM programmes WHERE epgId = :epgId AND stop > :from AND start < :to")
    suspend fun deleteRange(epgId: String, from: Long, to: Long)

    /** Swaps in fresher programmes for the span they cover; the rest of the schedule is kept. */
    @Transaction
    suspend fun replaceRange(epgId: String, items: List<ProgrammeEntity>) {
        if (items.isEmpty()) return
        deleteRange(epgId, items.minOf { it.start }, items.maxOf { it.stop })
        insertAll(items)
    }

    @Insert
    suspend fun insertAll(items: List<ProgrammeEntity>)
}

@Dao
interface UserDao {
    @Transaction
    @Query("SELECT `key` FROM favorites ORDER BY addedAt")
    fun observeFavoriteKeys(): Flow<List<String>>

    @Query("SELECT * FROM favorites ORDER BY addedAt")
    suspend fun allFavorites(): List<FavoriteEntity>

    @Query("SELECT * FROM progress")
    suspend fun allProgress(): List<ProgressEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE `key` = :key)")
    suspend fun isFavorite(key: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(f: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE `key` = :key")
    suspend fun removeFavorite(key: String)

    @Transaction
    @Query("SELECT `key` FROM history ORDER BY watchedAt DESC LIMIT 30")
    fun observeHistoryKeys(): Flow<List<String>>

    @Upsert
    suspend fun touchHistory(h: HistoryEntity)

    @Transaction
    @Query("SELECT * FROM progress WHERE positionMs > 30000 AND (durationMs = 0 OR positionMs < durationMs * 0.95) ORDER BY updatedAt DESC LIMIT 20")
    fun observeContinueWatching(): Flow<List<ProgressEntity>>

    @Query("SELECT * FROM progress WHERE `key` = :key")
    suspend fun progress(key: String): ProgressEntity?

    @Query("SELECT * FROM progress WHERE `key` IN (:keys)")
    suspend fun progressForKeys(keys: List<String>): List<ProgressEntity>

    @Query("DELETE FROM progress WHERE `key` = :key")
    suspend fun deleteProgress(key: String)

    @Query("DELETE FROM history WHERE `key` = :key")
    suspend fun deleteHistory(key: String)

    @Upsert
    suspend fun saveProgress(p: ProgressEntity)
}

@Database(
    entities = [
        SourceEntity::class, ChannelEntity::class, VodEntity::class, ProgrammeEntity::class,
        FavoriteEntity::class, HistoryEntity::class, ProgressEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class EdgeDatabase : RoomDatabase() {
    abstract fun sources(): SourceDao
    abstract fun channels(): ChannelDao
    abstract fun vod(): VodDao
    abstract fun programmes(): ProgrammeDao
    abstract fun user(): UserDao

    companion object {
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sources ADD COLUMN guideMatched INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sources ADD COLUMN guideUrlsUsed TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE channels ADD COLUMN offline INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE channels ADD COLUMN tvgId TEXT")
                db.execSQL("UPDATE channels SET tvgId = epgId")
            }
        }

        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE programmes ADD COLUMN subtitle TEXT")
                db.execSQL("ALTER TABLE programmes ADD COLUMN year INTEGER")
                db.execSQL("ALTER TABLE programmes ADD COLUMN rating TEXT")
            }
        }

        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE channels ADD COLUMN identity TEXT")
            }
        }

        fun create(context: Context): EdgeDatabase {
            val passphrase = DatabaseKey.passphrase(context, NAME)
            DatabaseKey.encryptExisting(context, NAME, passphrase)
            return Room.databaseBuilder(context, EdgeDatabase::class.java, NAME)
                .openHelperFactory(net.zetetic.database.sqlcipher.SupportOpenHelperFactory(passphrase.toByteArray(Charsets.UTF_8)))
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        }

        private const val NAME = "edgestream.db"
    }
}
