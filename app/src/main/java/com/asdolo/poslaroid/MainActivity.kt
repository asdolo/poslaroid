package com.asdolo.poslaroid

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraProvider
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.dantsu.escposprinter.EscPosPrinter
import com.dantsu.escposprinter.connection.bluetooth.BluetoothConnection
import com.dantsu.escposprinter.connection.bluetooth.BluetoothPrintersConnections
import com.dantsu.escposprinter.textparser.PrinterTextParserImg
import com.asdolo.poslaroid.databinding.ActivityMainBinding
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "Poslaroid"
private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding
    private var webServer: WebServer? = null

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var flashMode: Int = ImageCapture.FLASH_MODE_OFF
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var printer: EscPosPrinter? = null
    private var connection: BluetoothConnection? = null
    private var takePhotoMode: Boolean = false
    private var backPressedTime: Long = 0

    private val requiredPermissions = mutableListOf(
        Manifest.permission.CAMERA,
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN,
        Manifest.permission.INTERNET,
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.ACCESS_NETWORK_STATE
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        }
    }.toTypedArray()

    private val activityResultLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in requiredPermissions && !it.value)
                    permissionGranted = false
            }
            if (permissionGranted) {
                startCamera()
                mirrorCamera()
            } else {
                Toast.makeText(this, "Permission request denied", Toast.LENGTH_SHORT).show()
            }
        }

    private inner class WebServer(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            when (session.uri) {
                "/" -> {
                    val inputStream = assets.open("upload.html")
                    return newChunkedResponse(
                        Response.Status.OK,
                        "text/html",
                        inputStream
                    )
                }
                "/upload" -> {
                    if (session.method == Method.POST) {
                        val files = HashMap<String, String>()
                        session.parseBody(files)
                        
                        val tempFile = files["image"]
                        val customText = session.parameters["text"]?.get(0) // Get the text parameter
                        
                        if (tempFile != null) {
                            val originalBitmap = BitmapFactory.decodeFile(tempFile)
                            if (originalBitmap != null) {
                                // Create a new bitmap with white background
                                val finalBitmap = Bitmap.createBitmap(originalBitmap.width, originalBitmap.height, Bitmap.Config.ARGB_8888)
                                val canvas = Canvas(finalBitmap)
                                
                                // Draw white background
                                canvas.drawColor(Color.WHITE)
                                // Draw original bitmap on top
                                canvas.drawBitmap(originalBitmap, 0f, 0f, null)
                                
                                runOnUiThread {
                                    // Pass the custom text to the print function with the new bitmap
                                    printBluetooth(finalBitmap, customText)
                                }
                                return newFixedLengthResponse("Image received and printing started")
                            }
                        }
                        return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "No image received")
                    }
                    return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "POST method required")
                }
                else -> return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable back button gesture handling
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (takePhotoMode) {
                    takePhotoMode = false
                    updateBackButtonVisibility()
                } else {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - backPressedTime > 2000) {
                        backPressedTime = currentTime
                        Toast.makeText(this@MainActivity, R.string.press_back_again, Toast.LENGTH_SHORT).show()
                    } else {
                        finish()
                    }
                }
            }
        })
        
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        supportActionBar?.hide()

        // Set default footer text
        val currentDate = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
        viewBinding.footerText.setText("[L]\n<b><font size='tall'>$currentDate</font></b>\n[L]Gracias!\n[L]\n[L]\n[L]")

        if (allPermissionsGranted()) {
            startCamera()
            mirrorCamera()
        } else {
            requestPermissions()
        }

        viewBinding.imageCaptureButton.setOnClickListener { takePhoto() }
        viewBinding.flashToggleButton.setOnClickListener { toggleFlash() }
        viewBinding.switchCamera.setOnClickListener { switchCamera() }
        viewBinding.mirrorCamera.setOnClickListener {
            mirrorCamera()
        }

        viewBinding.backButton.setOnClickListener {
            takePhotoMode = false
            updateBackButtonVisibility()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Initialize printer connection
        connection = BluetoothPrintersConnections.selectFirstPaired()
        if (connection == null) {
            Toast.makeText(baseContext, "Es necesario ir a los ajustes de bluetooth y vincular la impresora", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(baseContext, "Conectado al dispositivo: " + connection?.device?.name, Toast.LENGTH_SHORT).show()
        }
        printer = EscPosPrinter(connection, 203, 48f, 32)

        // Start the web server
        try {
            webServer = WebServer(8080)
            webServer?.start()
            val ipAddress = getLocalIpAddress()
            Toast.makeText(
                this,
                "Web server started at http://$ipAddress:8080",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Failed to start web server: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
            e.printStackTrace()
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "127.0.0.1"
    }

    private fun mirrorCamera() {
        viewBinding.viewFinder.scaleX *= -1f
    }

    private fun requestPermissions() {
        activityResultLauncher.launch(requiredPermissions)
    }

    private fun allPermissionsGranted() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun switchCamera() {
        cameraSelector = when (cameraSelector) {
            CameraSelector.DEFAULT_BACK_CAMERA -> CameraSelector.DEFAULT_FRONT_CAMERA
            else -> CameraSelector.DEFAULT_BACK_CAMERA
        }
        startCamera()
    }

    private fun toggleFlash() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> {
                viewBinding.flashToggleButton.setText(R.string.flash_turn_off)
                ImageCapture.FLASH_MODE_ON
            }
            else -> {
                viewBinding.flashToggleButton.setText(R.string.flash_turn_on)
                ImageCapture.FLASH_MODE_OFF
            }
        }
        imageCapture?.flashMode = flashMode
    }

    private fun updateBackButtonVisibility() {
        viewBinding.backButton.visibility = if (takePhotoMode) View.VISIBLE else View.GONE
        viewBinding.printing.visibility = View.INVISIBLE
        viewBinding.viewFinder.visibility = View.VISIBLE
        viewBinding.flashToggleButton.visibility = View.VISIBLE
        viewBinding.switchCamera.visibility = View.VISIBLE
        viewBinding.mirrorCamera.visibility = View.VISIBLE
        viewBinding.footerText.visibility = View.VISIBLE
    }

    private fun takePhoto() {
        takePhotoMode = true
        updateBackButtonVisibility()
        viewBinding.printing.visibility = View.VISIBLE
        viewBinding.viewFinder.visibility = View.INVISIBLE
        viewBinding.flashToggleButton.visibility = View.INVISIBLE
        viewBinding.switchCamera.visibility = View.INVISIBLE
        viewBinding.mirrorCamera.visibility = View.INVISIBLE
        viewBinding.footerText.visibility = View.INVISIBLE

        val imageCapture = imageCapture ?: return

        val date = System.currentTimeMillis()
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(date)
        
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            .build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Sacar foto falló: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val inputStream: InputStream = contentResolver.openInputStream(output.savedUri ?: return) ?: return
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream.close()

                    printBluetooth(bitmap)
                    viewBinding.printing.visibility = View.INVISIBLE
                    viewBinding.viewFinder.visibility = View.VISIBLE
                }
            }
        )
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .setFlashMode(flashMode)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageCapture
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun printBluetooth(bitmap: Bitmap, customText: String? = null) {
        Log.i(TAG, "starting print")
        try {
            // Resize the bitmap
            val resizedBitmap = Bitmap.createScaledBitmap(
                bitmap,
                384,
                (384 * bitmap.height / bitmap.width.toFloat()).toInt(),
                true
            )
            Log.i(TAG, "resized")
            
            // Convert to grayscale
            val grayscaleBitmap = toGrayscale(resizedBitmap)
            Log.i(TAG, "grayscaled")
            
            // Apply dithering
            val ditheredBitmap = floydSteinbergDithering(grayscaleBitmap)
            Log.i(TAG, "floydsteinberg")

            // Build the text for printing
            val text = StringBuilder()
            
            // Process bitmap in 32-pixel height segments
            for (y in 0 until ditheredBitmap.height step 32) {
                val segmentHeight = if (y + 32 > ditheredBitmap.height) ditheredBitmap.height - y else 32
                val segment = Bitmap.createBitmap(ditheredBitmap, 0, y, ditheredBitmap.width, segmentHeight)
                text.append("<img>" + PrinterTextParserImg.bitmapToHexadecimalString(printer, segment, false) + "</img>\n")
            }

            // Use custom text if provided, otherwise use default text from viewBinding.footerText
            val footerText = customText ?: viewBinding.footerText.text.toString()

            // Connect to printer and print
            connection?.connect()
            printer?.printFormattedText(text.toString() + footerText)
            connection?.disconnect()

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(baseContext, e.message, Toast.LENGTH_LONG).show()
        }
    }

    fun toGrayscale(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val grayPixels = IntArray(width * height)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            val gray = (0.3 * red + 0.59 * green + 0.11 * blue).toInt()
            val grayPixel = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
            grayPixels[i] = grayPixel
        }

        val grayBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        grayBitmap.setPixels(grayPixels, 0, width, 0, 0, width, height)

        return grayBitmap
    }

    fun floydSteinbergDithering(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val ditheredBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val errorDiffusionMatrix = arrayOf(
            intArrayOf(0, 0, 0, 7),
            intArrayOf(3, 5, 1, 0)
        )

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val oldPixel = pixels[index]
                val oldGray = Color.red(oldPixel)
                val newGray = if (oldGray > 128) 255 else 0
                val error = oldGray - newGray

                pixels[index] = Color.rgb(newGray, newGray, newGray)

                for (dy in errorDiffusionMatrix.indices) {
                    for (dx in errorDiffusionMatrix[dy].indices) {
                        val newX = x + dx - 1
                        val newY = y + dy
                        if (newX in 0 until width && newY in 0 until height) {
                            val neighborIndex = newY * width + newX
                            val neighbor = pixels[neighborIndex]
                            val neighborGray = Color.red(neighbor)
                            val diffusedError = error * errorDiffusionMatrix[dy][dx] / 16
                            val newNeighborGray = (neighborGray + diffusedError).coerceIn(0, 255)
                            pixels[neighborIndex] = Color.rgb(
                                newNeighborGray,
                                newNeighborGray,
                                newNeighborGray
                            )
                        }
                    }
                }
            }
        }

        ditheredBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return ditheredBitmap
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        webServer?.stop()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
    }
}
