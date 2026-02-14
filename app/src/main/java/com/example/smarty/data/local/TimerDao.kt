package com.example.smarty.data.local

import androidx.room.*
import com.example.smarty.core.domain.model.SmartyTimer
import kotlinx.coroutines.flow.Flow

@Dao
interface TimerDao {
    @Query("SELECT * FROM timers WHERE isActive = 1")
    fun getActiveTimers(): Flow<List<SmartyTimer>>

    @Query("SELECT * FROM timers WHERE isActive = 1")
    suspend fun getActiveTimersOnce(): List<SmartyTimer>

    @Query("SELECT * FROM timers WHERE id = :id")
    suspend fun getTimerById(id: String): SmartyTimer?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTimer(timer: SmartyTimer)

    @Update
    suspend fun updateTimer(timer: SmartyTimer)

    @Delete
    suspend fun deleteTimer(timer: SmartyTimer)

    @Query("DELETE FROM timers WHERE id = :id")
    suspend fun deleteTimerById(id: String)

    @Query("UPDATE timers SET isActive = 0 WHERE id = :id")
    suspend fun deactivateTimer(id: String)
}
