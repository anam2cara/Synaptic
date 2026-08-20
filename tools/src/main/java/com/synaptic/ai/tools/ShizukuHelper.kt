package com.synaptic.ai.tools

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

object ShizukuHelper {

    private const val SHIZUKU_PERMISSION_REQUEST_CODE = 2401

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            ShizukuState.granted = granted
            ShizukuState.requestInFlight = false
            // Kalau user menolak, jangan otomatis nyodorin dialog lagi di setiap
            // onResume() -- itu yang bikin RequestPermissionActivity muncul berulang.
            ShizukuState.userDenied = !granted
        }

    fun init() {
        try {
            Shizuku.addRequestPermissionResultListener(permissionListener)
            Shizuku.addBinderReceivedListener {
                updateState()
            }
        } catch (_: Exception) {}
        updateState()
    }

    fun updateState() {
        ShizukuState.available = isShizukuAvailable()
        ShizukuState.granted = hasPermission()
    }

    fun isShizukuAvailable(): Boolean {
        val available = try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
        android.util.Log.d("ShizukuHelper", "isShizukuAvailable: $available")
        return available
    }

    fun hasPermission(): Boolean {
        if (!isShizukuAvailable())
            return false

        val granted = if (Shizuku.isPreV11()) {
            false
        } else {
            Shizuku.checkSelfPermission() ==
                PackageManager.PERMISSION_GRANTED
        }
        android.util.Log.d("ShizukuHelper", "hasPermission: $granted")
        return granted
    }

    /**
     * Minta izin Shizuku.
     * @param force Lewati guard userDenied -- pakai ini HANYA dari aksi eksplisit user
     *              (misal tombol "Coba lagi" di Settings), bukan dari lifecycle callback
     *              otomatis (onResume/LaunchedEffect), supaya tidak nge-loop nge-spam dialog.
     */
    fun requestPermission(force: Boolean = false) {
        android.util.Log.d("ShizukuHelper", "requestPermission() called force=$force")
        if (!isShizukuAvailable() || hasPermission()) return
        if (ShizukuState.requestInFlight) {
            android.util.Log.d("ShizukuHelper", "Skip: dialog permission masih pending")
            return
        }
        if (ShizukuState.userDenied && !force) {
            android.util.Log.d("ShizukuHelper", "Skip: user sudah pernah menolak, tidak auto-prompt lagi")
            return
        }
        android.util.Log.d("ShizukuHelper", "Triggering Shizuku.requestPermission")
        ShizukuState.requestInFlight = true
        Shizuku.requestPermission(
            SHIZUKU_PERMISSION_REQUEST_CODE
        )
    }

    /**
     * Menjalankan perintah ADB melalui Shizuku.
     * Menggunakan ShellExecutor untuk menangani proses JNI/Shizuku.
     */
    fun runAdbCommand(command: String): String {
        return if (isShizukuAvailable()) {
            if (hasPermission()) {
                ShellExecutor.run(command)
            } else {
                "Error: Izin Shizuku belum diberikan."
            }
        } else {
            "Error: Layanan Shizuku tidak terhubung/aktif."
        }
    }
}
