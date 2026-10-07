package app.lumen.photos.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Image embedding of one media item for one model, stored as IEEE half floats. */
@Entity(tableName = "embeddings", primaryKeys = ["mediaId", "modelId"])
class EmbeddingEntity(
    val mediaId: Long,
    val modelId: String,
    val dateModified: Long,
    /** Empty if the file could not be processed (so it is not retried on every run). */
    val vector: ByteArray,
)

data class EmbeddingKey(val mediaId: Long, val dateModified: Long)

@Entity(tableName = "optimized", primaryKeys = ["mediaId"])
data class OptimizedEntity(
    val mediaId: Long,
    val originalSize: Long,
    val newSize: Long,
    val width: Int,
    val height: Int,
    val timestamp: Long,
)

data class OptimizedTotals(val count: Int, val saved: Long?)

@Dao
interface EmbeddingDao {
    @Query("SELECT mediaId, dateModified FROM embeddings WHERE modelId = :modelId")
    suspend fun keys(modelId: String): List<EmbeddingKey>

    @Query("SELECT * FROM embeddings WHERE modelId = :modelId AND length(vector) > 0 ORDER BY mediaId LIMIT :limit OFFSET :offset")
    suspend fun page(modelId: String, limit: Int, offset: Int): List<EmbeddingEntity>

    @Query("SELECT * FROM embeddings WHERE modelId = :modelId AND mediaId = :mediaId")
    suspend fun get(modelId: String, mediaId: Long): EmbeddingEntity?

    @Upsert
    suspend fun upsert(items: List<EmbeddingEntity>)

    @Query("DELETE FROM embeddings WHERE modelId = :modelId AND mediaId IN (:ids)")
    suspend fun delete(modelId: String, ids: List<Long>)

    @Query("DELETE FROM embeddings WHERE modelId = :modelId")
    suspend fun deleteModel(modelId: String)

    @Query("SELECT COUNT(*) FROM embeddings WHERE modelId = :modelId")
    fun countFlow(modelId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM embeddings WHERE modelId = :modelId")
    suspend fun count(modelId: String): Int

    /** Marks a file as re-encoded in place: its content is unchanged, so the next run keeps the vector. */
    @Query("UPDATE embeddings SET dateModified = -1 WHERE mediaId = :mediaId")
    suspend fun markReplaced(mediaId: Long)

    @Query("UPDATE embeddings SET dateModified = :dateModified WHERE mediaId = :mediaId AND dateModified = -1")
    suspend fun acceptReplaced(mediaId: Long, dateModified: Long)
}

@Dao
interface OptimizedDao {
    @Query("SELECT mediaId FROM optimized")
    suspend fun ids(): List<Long>

    @Upsert
    suspend fun upsert(item: OptimizedEntity)

    @Query("SELECT COUNT(*) AS count, SUM(originalSize - newSize) AS saved FROM optimized")
    fun totals(): Flow<OptimizedTotals>
}

// ---------------------------------------------------------------- Faces

/** One detected face. Box is normalised (0..1) relative to the oriented image. */
@Entity(tableName = "faces", indices = [Index("mediaId"), Index("personId"), Index("modelId")])
class FaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: Long,
    val modelId: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val score: Float,
    val embedding: ByteArray,
    val personId: Long? = null,
    /** Confirmed by the user (swipe / manual assignment) – never moved automatically. */
    val confirmed: Boolean = false,
)

/** Remembers which files have been scanned for faces (also when none were found). */
@Entity(tableName = "face_scans", primaryKeys = ["mediaId", "modelId"])
data class FaceScanEntity(val mediaId: Long, val modelId: String, val dateModified: Long, val faces: Int)

@Entity(tableName = "persons", indices = [Index("modelId")])
class PersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val modelId: String,
    val name: String? = null,
    val hidden: Boolean = false,
    /** Mean of the member embeddings (fp16, L2-normalised). */
    val centroid: ByteArray,
    val faceCount: Int = 0,
    val coverFaceId: Long? = null,
)

/** "This face is not that person" – the result of swiping left. */
@Entity(tableName = "face_rejections", primaryKeys = ["faceId", "personId"], indices = [Index("personId")])
data class FaceRejectionEntity(val faceId: Long, val personId: Long)

data class PersonMedia(val personId: Long, val mediaId: Long)

@Dao
interface FaceDao {
    @Query("SELECT mediaId, dateModified FROM face_scans WHERE modelId = :modelId")
    suspend fun scanKeys(modelId: String): List<EmbeddingKey>

    @Upsert
    suspend fun upsertScan(scan: FaceScanEntity)

    @Query("DELETE FROM face_scans WHERE modelId = :modelId AND mediaId IN (:ids)")
    suspend fun deleteScans(modelId: String, ids: List<Long>)

    @Query("UPDATE face_scans SET dateModified = -1 WHERE mediaId = :mediaId")
    suspend fun markReplaced(mediaId: Long)

    @Query("UPDATE face_scans SET dateModified = :dateModified WHERE mediaId = :mediaId AND dateModified = -1")
    suspend fun acceptReplaced(mediaId: Long, dateModified: Long)

    @Insert
    suspend fun insertFaces(faces: List<FaceEntity>): List<Long>

    @Query("SELECT * FROM faces WHERE modelId = :modelId AND mediaId IN (:ids)")
    suspend fun facesOfMedia(modelId: String, ids: List<Long>): List<FaceEntity>

    @Query("DELETE FROM faces WHERE modelId = :modelId AND mediaId IN (:ids)")
    suspend fun deleteFacesOfMedia(modelId: String, ids: List<Long>)

    @Query("SELECT * FROM faces WHERE id = :id")
    suspend fun face(id: Long): FaceEntity?

    @Query("SELECT * FROM faces WHERE personId = :personId")
    suspend fun facesOfPerson(personId: Long): List<FaceEntity>

    @Query("SELECT * FROM faces WHERE modelId = :modelId")
    suspend fun allFaces(modelId: String): List<FaceEntity>

    @Query("SELECT * FROM faces WHERE modelId = :modelId AND personId IS NULL")
    suspend fun unassigned(modelId: String): List<FaceEntity>

    @Query("UPDATE faces SET personId = :personId, confirmed = :confirmed WHERE id = :faceId")
    suspend fun assign(faceId: Long, personId: Long?, confirmed: Boolean)

    @Query("UPDATE faces SET personId = :to WHERE personId = :from")
    suspend fun moveFaces(from: Long, to: Long)

    @Query("UPDATE faces SET personId = NULL WHERE modelId = :modelId AND confirmed = 0")
    suspend fun clearUnconfirmed(modelId: String)

    @Query("DELETE FROM faces WHERE modelId = :modelId")
    suspend fun deleteAllFaces(modelId: String)

    @Query("DELETE FROM face_scans WHERE modelId = :modelId")
    suspend fun deleteAllScans(modelId: String)

    @Query("SELECT COUNT(*) FROM face_scans WHERE modelId = :modelId")
    fun scanCount(modelId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM faces WHERE modelId = :modelId")
    fun faceCount(modelId: String): Flow<Int>

    // Persons
    @Insert
    suspend fun insertPerson(person: PersonEntity): Long

    @Upsert
    suspend fun upsertPerson(person: PersonEntity)

    @Query("SELECT * FROM persons WHERE modelId = :modelId")
    suspend fun persons(modelId: String): List<PersonEntity>

    @Query("SELECT * FROM persons WHERE modelId = :modelId")
    fun personsFlow(modelId: String): Flow<List<PersonEntity>>

    @Query("SELECT * FROM persons WHERE id = :id")
    suspend fun person(id: Long): PersonEntity?

    @Query("SELECT * FROM persons WHERE id = :id")
    fun personFlow(id: Long): Flow<PersonEntity?>

    @Query("DELETE FROM persons WHERE id = :id")
    suspend fun deletePerson(id: Long)

    @Query("UPDATE persons SET hidden = :hidden WHERE id IN (:ids)")
    suspend fun setHidden(ids: List<Long>, hidden: Boolean)

    @Query("DELETE FROM persons WHERE modelId = :modelId AND name IS NULL AND id NOT IN (SELECT DISTINCT personId FROM faces WHERE confirmed = 1 AND personId IS NOT NULL)")
    suspend fun deleteUnnamedPersons(modelId: String)

    @Query("DELETE FROM persons WHERE modelId = :modelId")
    suspend fun deleteAllPersons(modelId: String)

    @Query("SELECT DISTINCT personId, mediaId FROM faces WHERE modelId = :modelId AND personId IS NOT NULL")
    fun personMediaFlow(modelId: String): Flow<List<PersonMedia>>

    @Query("SELECT DISTINCT mediaId FROM faces WHERE personId = :personId")
    suspend fun mediaOfPerson(personId: Long): List<Long>

    // Rejections
    @Upsert
    suspend fun reject(rejection: FaceRejectionEntity)

    @Query("DELETE FROM face_rejections WHERE faceId = :faceId AND personId = :personId")
    suspend fun unreject(faceId: Long, personId: Long)

    @Query("SELECT faceId FROM face_rejections WHERE personId = :personId")
    suspend fun rejectedFaces(personId: Long): List<Long>

    @Query("SELECT * FROM face_rejections")
    suspend fun allRejections(): List<FaceRejectionEntity>

    @Query("UPDATE face_rejections SET personId = :to WHERE personId = :from")
    suspend fun moveRejections(from: Long, to: Long)
}

@Database(
    entities = [
        EmbeddingEntity::class, OptimizedEntity::class,
        FaceEntity::class, FaceScanEntity::class, PersonEntity::class, FaceRejectionEntity::class,
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class LumenDatabase : RoomDatabase() {
    abstract fun embeddings(): EmbeddingDao
    abstract fun optimized(): OptimizedDao
    abstract fun faces(): FaceDao

    companion object {
        fun create(context: Context): LumenDatabase =
            Room.databaseBuilder(context, LumenDatabase::class.java, "lumen.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
