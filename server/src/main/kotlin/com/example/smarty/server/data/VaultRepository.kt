package com.example.smarty.server.data

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class E2EVaultData(
    val userId: String,
    val encryptedBlob: String,
    val version: Int,
    val updatedAt: Long
)

object UserVaults : Table("user_vaults") {
    val userId = varchar("user_id", 128)
    val encryptedBlob = text("encrypted_blob") // Base64 encoded encrypted data
    val version = integer("version").default(1)
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(userId)
}

class VaultRepository(private val database: Database) {
    
    init {
        transaction(database) {
            SchemaUtils.create(UserVaults)
        }
    }

    suspend fun get(userId: String): E2EVaultData? = dbQuery {
        UserVaults.selectAll().where { UserVaults.userId eq userId }
            .map { row ->
                E2EVaultData(
                    userId = row[UserVaults.userId],
                    encryptedBlob = row[UserVaults.encryptedBlob],
                    version = row[UserVaults.version],
                    updatedAt = row[UserVaults.updatedAt]
                )
            }
            .singleOrNull()
    }

    suspend fun store(userId: String, encryptedBlob: String, version: Int): Boolean = dbQuery {
        val existing = UserVaults.selectAll().where { UserVaults.userId eq userId }.count() > 0
        if (existing) {
            UserVaults.update({ UserVaults.userId eq userId }) {
                it[UserVaults.encryptedBlob] = encryptedBlob
                it[UserVaults.version] = version
                it[UserVaults.updatedAt] = System.currentTimeMillis()
            } > 0
        } else {
            UserVaults.insert {
                it[UserVaults.userId] = userId
                it[UserVaults.encryptedBlob] = encryptedBlob
                it[UserVaults.version] = version
                it[UserVaults.updatedAt] = System.currentTimeMillis()
            }
            true
        }
    }

    suspend fun delete(userId: String): Boolean = dbQuery {
        UserVaults.deleteWhere { UserVaults.userId eq userId } > 0
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }
}
