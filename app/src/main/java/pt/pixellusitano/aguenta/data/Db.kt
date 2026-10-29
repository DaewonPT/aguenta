package pt.pixellusitano.aguenta.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
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
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import pt.pixellusitano.aguenta.domain.TxKind
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Entity(tableName = "transactions", indices = [Index("timestamp")])
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val deltaCents: Long, // negativo = gasto
    val kind: TxKind,
    val merchant: String?,
    val sourcePackage: String,
    val category: String,
    val essential: Boolean = false,
)

@Entity(tableName = "cycles")
data class BudgetCycleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMillis: Long, // em milissegundos: com a âncora Wise o ciclo começa à hora a que o salário cai
    val endEpochDay: Long,  // dia do próximo salário (exclusivo)
    val openingCents: Long,
)

enum class IncomeType { FIXED, VARIABLE }
enum class CycleAnchor { DAY, WISE_INCOME }

@Entity(tableName = "income_config")
data class IncomeConfigEntity(
    @PrimaryKey val id: Int = 0,
    val type: IncomeType = IncomeType.FIXED,
    val paydayDay: Int = 28,
    val baseCents: Long = 0,
    val fixedCostsCents: Long = 0,
    val anchor: CycleAnchor = CycleAnchor.DAY,
    val ownNames: String = "", // separados por vírgulas
)

@Dao
interface AguentaDao {
    @Insert suspend fun insertTx(t: TransactionEntity): Long
    @Query("DELETE FROM transactions WHERE id = :id") suspend fun deleteTx(id: Long)
    @Query("DELETE FROM transactions WHERE timestamp < :before") suspend fun deleteOlderThan(before: Long): Int
    @Query("UPDATE transactions SET essential = :essential WHERE id = :id") suspend fun setEssential(id: Long, essential: Boolean)
    @Query("SELECT * FROM transactions WHERE kind = 'SPEND' ORDER BY timestamp DESC LIMIT 1") suspend fun lastSpend(): TransactionEntity?
    @Query("SELECT COUNT(*) FROM transactions WHERE deltaCents = :delta AND timestamp BETWEEN :from AND :to") suspend fun countSimilar(delta: Long, from: Long, to: Long): Int
    @Query("SELECT COALESCE(SUM(deltaCents), 0) FROM transactions WHERE timestamp >= :from AND timestamp < :to") suspend fun sumBetween(from: Long, to: Long): Long
    @Query("SELECT * FROM transactions WHERE timestamp >= :from ORDER BY timestamp DESC") fun txsSince(from: Long): Flow<List<TransactionEntity>>
    @Query("SELECT * FROM transactions WHERE timestamp >= :from ORDER BY timestamp DESC") suspend fun txsSinceNow(from: Long): List<TransactionEntity>
    @Query("SELECT * FROM transactions WHERE timestamp >= :from AND timestamp < :to") suspend fun txsBetween(from: Long, to: Long): List<TransactionEntity>

    @Insert suspend fun insertCycle(c: BudgetCycleEntity)
    @Query("UPDATE cycles SET endEpochDay = :end WHERE id = :id") suspend fun setCycleEnd(id: Long, end: Long)
    @Query("SELECT * FROM cycles ORDER BY id DESC LIMIT 1") fun cycle(): Flow<BudgetCycleEntity?>
    @Query("SELECT * FROM cycles ORDER BY id DESC LIMIT 1") suspend fun currentCycle(): BudgetCycleEntity?
    @Query("SELECT * FROM cycles ORDER BY id DESC LIMIT 2") fun lastTwoCycles(): Flow<List<BudgetCycleEntity>>
    @Query("SELECT * FROM cycles ORDER BY id DESC LIMIT 2") suspend fun lastTwoCyclesNow(): List<BudgetCycleEntity>

    @Query("SELECT * FROM income_config WHERE id = 0") fun config(): Flow<IncomeConfigEntity?>
    @Query("SELECT * FROM income_config WHERE id = 0") suspend fun configNow(): IncomeConfigEntity?
    @Upsert suspend fun saveConfig(c: IncomeConfigEntity)
}

@Database(entities = [TransactionEntity::class, BudgetCycleEntity::class, IncomeConfigEntity::class], version = 1, exportSchema = false)
abstract class AguentaDb : RoomDatabase() {
    abstract fun dao(): AguentaDao

    companion object {
        fun open(ctx: Context): AguentaDb {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(ctx, AguentaDb::class.java, "aguenta.db")
                .openHelperFactory(SupportOpenHelperFactory(DbKey.get(ctx)))
                .build()
        }
    }
}

/** Frase-passe aleatória da BD, guardada cifrada por uma chave AES que nunca sai do Android Keystore. */
private object DbKey {
    private const val ALIAS = "aguenta_db"

    fun get(ctx: Context): ByteArray {
        val prefs = ctx.getSharedPreferences("db_key", Context.MODE_PRIVATE)
        val secret = keystoreKey()
        prefs.getString("wrapped", null)?.let { stored ->
            val (iv, data) = stored.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, iv))
                doFinal(data)
            }
        }
        val raw = ByteArray(32).also(SecureRandom()::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secret) }
        val wrapped = c.doFinal(raw)
        prefs.edit().putString("wrapped", "${b64(c.iv)}:${b64(wrapped)}").commit()
        return raw
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as SecretKey?)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }
}
