package com.lxpro.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lxpro.core.database.entity.UserApiScriptEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserApiScriptDao {

    @Query("SELECT * FROM user_api_scripts ORDER BY importedAt DESC")
    fun observeAll(): Flow<List<UserApiScriptEntity>>

    @Query("SELECT * FROM user_api_scripts WHERE id = :id")
    suspend fun byId(id: String): UserApiScriptEntity?

    @Query("SELECT * FROM user_api_scripts WHERE enabled = 1 ORDER BY importedAt")
    suspend fun enabledScripts(): List<UserApiScriptEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: UserApiScriptEntity)

    @Update
    suspend fun update(entity: UserApiScriptEntity)

    @Delete
    suspend fun delete(entity: UserApiScriptEntity)
}
