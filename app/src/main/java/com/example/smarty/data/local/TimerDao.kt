package com.example.smarty.data.local

import androidx.room.*
import com.example.smarty.data.model.JarvisTimer
import kotlinx.coroutines.flow.Flow

@Dao
interface TimerDao {
    @Query("SELECT * FROM timers WHERE isActive = 1")
    fun getActiveTimers(): Flow<List<JarvisTimer>>

    @Query("SELECT * FROM timers WHERE isActive = 1")
    suspend fun getActiveTimersOnce(): List<JarvisTimer>

    @Query("SELECT * FROM timers WHERE id = :id")
    suspend fun getTimerById(id: String): JarvisTimer?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTimer(timer: JarvisTimer)

    @Update
    suspend fun updateTimer(timer: JarvisTimer)

    @Delete
    suspend fun deleteTimer(timer: JarvisTimer)

    @Query("DELETE FROM timers WHERE id = :id")
    suspend fun deleteTimerById(id: String)

    @Query("UPDATE timers SET isActive = 0 WHERE id = :id")
    suspend fun deactivateTimer(id: String)
}
