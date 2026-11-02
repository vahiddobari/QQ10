package com.hoosha.qx10pro

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private var liveview: LiveviewStream? = null
    @Volatile private var timelapseRunning = false

    private val fNumbers = listOf("3.3", "3.8", "4.3", "4.8", "5.3", "5.9", "6.7")
    // EV values corresponding to 0..12 index (-6 to +6 in steps of 1/3 EV)
    private val evValues = listOf("-2.0", "-1.7", "-1.3", "-1.0", "-0.7", "-0.3", "±0.0", "+0.3", "+0.7", "+1.0", "+1.3", "+1.7", "+2.0")

    private lateinit var statusText: TextView
    private lateinit var liveImageView: ImageView
    private lateinit var focusIndicator: View
    private lateinit var btnCancelAf: Button
    private lateinit var evLabel: TextView
    private lateinit var evSeek: SeekBar
    private lateinit var bokehLabel: TextView
    private lateinit var bokehSeek: SeekBar

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        liveImageView = findViewById(R.id.liveView)
        focusIndicator = findViewById(R.id.focusIndicator)
        btnCancelAf = findViewById(R.id.btnCancelAf)
        evLabel = findViewById(R.id.evLabel)
        evSeek = findViewById(R.id.evSeek)
        bokehLabel = findViewById(R.id.bokehLabel)
        bokehSeek = findViewById(R.id.bokehSeek)

        val isos = arrayOf("AUTO", "100", "200", "400", "800", "1600", "3200", "6400", "12800")
        val shutters = arrayOf("AUTO", "30", "15", "8", "4", "2", "1", "1/4", "1/8", "1/15", "1/30", "1/60", "1/125", "1/250", "1/500", "1/1000", "1/1600", "1/2000")

        val spinIso = findViewById<Spinner>(R.id.spinIso)
        val spinShutter = findViewById<Spinner>(R.id.spinShutter)
        spinIso.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, isos)
        spinShutter.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, shutters)

        spinIso.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val iso = isos[pos]
                io.execute {
                    SonyCamera.setIso(iso)
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        spinShutter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val shutter = shutters[pos]
                if (shutter != "AUTO") {
                    io.execute { SonyCamera.setShutterSpeed(shutter) }
                } else {
                    io.execute { SonyCamera.setExposureMode("Program Auto") }
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // Connection
        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            statusText.text = "Searching camera on WiFi..."
            SonyCamera.discover { ep ->
                runOnUiThread {
                    SonyCamera.endpoint = ep ?: ""
                    statusText.text = "Connected: $ep"
                    openLiveview()
                    loadCameraSettings()
                }
            }
        }

        // Touch AF on LiveView
        liveImageView.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_UP) {
                val width = view.width.toFloat()
                val height = view.height.toFloat()
                if (width > 0 && height > 0) {
                    val xPercent = (event.x / width * 100.0).coerceIn(0.0, 100.0)
                    val yPercent = (event.y / height * 100.0).coerceIn(0.0, 100.0)

                    // Position visual focus box
                    val halfBox = focusIndicator.width / 2
                    focusIndicator.x = (event.x - halfBox).coerceIn(0f, width - focusIndicator.width)
                    focusIndicator.y = (event.y - halfBox).coerceIn(0f, height - focusIndicator.height)
                    focusIndicator.visibility = View.VISIBLE
                    btnCancelAf.visibility = View.VISIBLE

                    // Send Touch AF RPC
                    io.execute {
                        val success = SonyCamera.setTouchAFPosition(xPercent, yPercent)
                        runOnUiThread {
                            if (!success) {
                                statusText.text = "Touch AF: position sent"
                            }
                        }
                    }
                }
            }
            true
        }

        btnCancelAf.setOnClickListener {
            focusIndicator.visibility = View.GONE
            btnCancelAf.visibility = View.GONE
            io.execute { SonyCamera.cancelTouchAFPosition() }
        }

        // Shutter & Burst
        findViewById<Button>(R.id.btnShutter).setOnClickListener {
            io.execute { SonyCamera.actTakePicture() }
            Toast.makeText(this, "Photo taken", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnBurst).setOnClickListener {
            io.execute {
                SonyCamera.setExposureMode("Continuous")
                SonyCamera.actTakePicture()
            }
        }

        // Zoom
        findViewById<Button>(R.id.btnZoomIn).setOnClickListener { io.execute { SonyCamera.setZoom("in") } }
        findViewById<Button>(R.id.btnZoomOut).setOnClickListener { io.execute { SonyCamera.setZoom("out") } }

        // Exposure Compensation (EV) Slider (-2.0 to +2.0 EV)
        evSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val evText = evValues.getOrElse(progress) { "±0.0" }
                evLabel.text = "Exposure Compensation (EV): $evText EV"
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {
                val idx = (sb?.progress ?: 6) - 6 // Relative index: -6 to +6
                io.execute { SonyCamera.setExposureCompensation(idx) }
            }
        })

        // Software Bokeh & Aperture
        bokehSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val f = fNumbers.getOrElse(progress) { "3.3" }
                bokehLabel.text = "Aperture / Bokeh: f/$f"
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {
                val f = fNumbers.getOrElse(sb?.progress ?: 0) { "3.3" }
                io.execute { SonyCamera.setFNumber(f) }
            }
        })

        // Timelapse
        findViewById<Button>(R.id.btnTimelapseStart).setOnClickListener {
            val sec = findViewById<EditText>(R.id.etInterval).text.toString().toLongOrNull() ?: 5L
            timelapseRunning = true
            io.execute {
                while (timelapseRunning) {
                    SonyCamera.actTakePicture()
                    try { TimeUnit.SECONDS.sleep(sec) } catch (_: InterruptedException) { break }
                }
            }
            Toast.makeText(this, "Timelapse started", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnTimelapseStop).setOnClickListener {
            timelapseRunning = false
            Toast.makeText(this, "Timelapse stopped", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadCameraSettings() {
        io.execute {
            val evInfo = SonyCamera.getAvailableExposureCompensation()
            if (evInfo != null) {
                val progress = (evInfo.current + 6).coerceIn(0, 12)
                runOnUiThread {
                    evSeek.progress = progress
                    evLabel.text = "Exposure Compensation (EV): ${evInfo.toEvString()}"
                }
            }
        }
    }

    private fun openLiveview() {
        io.execute {
            val url = SonyCamera.startLiveview()
            if (url == null) {
                runOnUiThread { statusText.text = "Liveview connection error" }
                return@execute
            }
            liveview?.stop()
            val stream = LiveviewStream(url) { jpegBytes ->
                val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                if (bmp != null) runOnUiThread { liveImageView.setImageBitmap(bmp) }
            }
            liveview = stream
            stream.start()
            runOnUiThread { statusText.text = "Live view active (Touch screen to focus)" }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        timelapseRunning = false
        liveview?.stop()
        io.shutdown()
    }
}
