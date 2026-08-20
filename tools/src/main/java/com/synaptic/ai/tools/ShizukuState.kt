package com.synaptic.ai.tools

object ShizukuState {
    @Volatile
    var granted:Boolean=false

    @Volatile
    var available:Boolean=false

    // Guard supaya requestPermission() tidak menembak dialog baru selagi
    // satu dialog permission masih menunggu keputusan user (root cause dari
    // RequestPermissionActivity yang muncul berkali-kali di logcat).
    @Volatile
    var requestInFlight: Boolean = false

    // Set true saat user secara eksplisit menolak izin Shizuku, supaya app
    // tidak menyodorkan dialog otomatis lagi di setiap onResume().
    @Volatile
    var userDenied: Boolean = false
}
