package com.dothakobi.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import java.io.ByteArrayOutputStream

/** ゲーム画面（assets/index.html）を表示し、写真の選択・撮影をつなぐ */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var photoUri: Uri? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.addJavascriptInterface(Bridge(), "Android")
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val i = Intent(Intent.ACTION_GET_CONTENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("image/*")
                return try {
                    startActivityForResult(Intent.createChooser(i, "写真を選ぶ"), REQ_PICK)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }
        }
        web.loadUrl("file:///android_asset/index.html")
    }

    inner class Bridge {
        /** カメラを起動。撮った写真は一時的に保存して読み込み、読み込んだら消す */
        @JavascriptInterface
        fun takePhoto() {
            runOnUiThread {
                try {
                    val cv = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "dothakobi_${System.currentTimeMillis()}.jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DotHakobi")
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                    photoUri = uri
                    val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    if (uri != null) i.putExtra(MediaStore.EXTRA_OUTPUT, uri)
                    startActivityForResult(i, REQ_CAMERA)
                } catch (e: Exception) {
                    sendPhoto(null)
                }
            }
        }
    }

    @Deprecated("")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_PICK -> {
                val uri = if (resultCode == RESULT_OK) data?.data else null
                fileCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
                fileCallback = null
            }
            REQ_CAMERA -> {
                val uri = photoUri
                photoUri = null
                var url: String? = null
                if (resultCode == RESULT_OK) {
                    url = try {
                        val bmp = if (uri != null) loadScaled(uri, 720)
                        else @Suppress("DEPRECATION") (data?.extras?.get("data") as? Bitmap)
                        bmp?.let { toDataUrl(it) }
                    } catch (e: Exception) { null }
                }
                if (uri != null) try { contentResolver.delete(uri, null, null) } catch (_: Exception) { }
                sendPhoto(url)
            }
        }
    }

    private fun loadScaled(uri: Uri, max: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth <= 0) return null
        var sample = 1
        while (o.outWidth / (sample * 2) >= max && o.outHeight / (sample * 2) >= max) sample *= 2
        val bmp = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        // 写真の向きを合わせる
        val rot = try {
            contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (e: Exception) { 0f }
        return if (rot == 0f) bmp
        else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
    }

    private fun toDataUrl(b: Bitmap): String {
        val out = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 88, out)
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun sendPhoto(url: String?) {
        val arg = if (url == null) "null" else "'$url'"
        web.post { web.evaluateJavascript("window.onNativePhoto&&onNativePhoto($arg)", null) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    }

    companion object {
        const val REQ_PICK = 7
        const val REQ_CAMERA = 8
    }
}
