package com.synaptic.ai.data.repo

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.synaptic.ai.data.model.ActionLog
import com.synaptic.ai.data.model.ChatMessage
import com.synaptic.ai.data.model.Memory

@Database(
    entities = [ChatMessage::class, Memory::class, ActionLog::class],
    version = 3,
    exportSchema = false
)
abstract class SynapticDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatDao
    abstract fun memoryDao(): MemoryDao
    abstract fun actionLogDao(): ActionLogDao

    companion object {
        @Volatile
        private var instance: SynapticDatabase? = null

        fun getInstance(context: Context): SynapticDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SynapticDatabase::class.java,
                    "synaptic_db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
