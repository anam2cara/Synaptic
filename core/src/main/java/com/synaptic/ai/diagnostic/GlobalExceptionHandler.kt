package com.synaptic.ai.diagnostic

import android.content.Context
import android.util.Log
import kotlin.system.exitProcess

class GlobalExceptionHandler(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            Log.e("SynapticCrash", "Uncaught exception in thread ${thread.name}", throwable)
            
            // Tambahkan breadcrumb terakhir
            DiagnosticManager.addBreadcrumb("CRASH_UNCAUGHT_EXCEPTION", "${throwable.javaClass.simpleName}: ${throwable.message}")
            
            // Tulis laporan
            DiagnosticManager.writeReport(context, throwable)
            
        } catch (e: Exception) {
            Log.e("SynapticCrash", "Error in crash handler", e)
        } finally {
            // Serahkan ke default handler atau exit
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable)
            } else {
                exitProcess(1)
            }
        }
    }

    companion object {
        fun install(context: Context) {
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(GlobalExceptionHandler(context, defaultHandler))
        }
    }
}
