package com.mka23.arisu

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle

class ScreenCaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    @Deprecated("Deprecated by Android, retained for API 23 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                val service = Intent(this, ScreenRecordService::class.java).apply {
                    action = ScreenRecordService.ACTION_START
                    putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(ScreenRecordService.EXTRA_RESULT_DATA, data)
                }
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
            }
            finish()
        }
    }

    companion object {
        private const val REQUEST_CAPTURE = 7401
    }
}
