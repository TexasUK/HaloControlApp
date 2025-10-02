package com.example.Halo

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import android.graphics.Typeface

class TickSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private lateinit var seekBar: SeekBar
    private lateinit var tickContainer: LinearLayout
    private var maxValue: Int = 100

    init {
        LayoutInflater.from(context).inflate(R.layout.custom_seekbar, this, true)
        seekBar = findViewById(R.id.seekBar)
        tickContainer = findViewById(R.id.tickContainer)

        // Get attributes
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.TickSeekBar)
        maxValue = typedArray.getInt(R.styleable.TickSeekBar_android_max, 100)
        val progress = typedArray.getInt(R.styleable.TickSeekBar_android_progress, 0)
        typedArray.recycle()

        setupSeekBar(maxValue, progress)
        setupTickMarks(maxValue)
    }

    private fun setupSeekBar(max: Int, progress: Int) {
        seekBar.max = max
        seekBar.progress = progress
    }

    private fun setupTickMarks(max: Int) {
        tickContainer.removeAllViews()

        // Determine the step for tick marks based on the max value
        val step = when {
            max <= 10 -> 1 // Volume: show every number (0-10)
            max == 100 -> 10 // Elevation: show every 100ft (0, 100, 200, ..., 1000)
            max == 200 -> 25 // QNH: show major values (800, 850, 900, ..., 1200)
            else -> max / 10 // Default: show 10 ticks
        }

        for (i in 0..max step step) {
            val tickView = TextView(context).apply {
                // Show appropriate labels based on the max value
                val textValue = when (max) {
                    in 0..10 -> i.toString() // Volume: show numbers 0-10
                    100 -> {
                        when (i * 10) {
                            1000 -> "1K" // Use 1K instead of 1000 to save space
                            else -> (i * 10).toString() // Elevation: show 0, 100, 200, ..., 900
                        }
                    }
                    200 -> {
                        val pressureValue = 800 + (i * 2)
                        when {
                            pressureValue >= 1000 -> (pressureValue / 10).toString() + "0" // Show 1000 as 100, 1050 as 105, etc.
                            else -> pressureValue.toString() // Show 800, 850, 900, 950
                        }
                    }
                    else -> i.toString() // Default
                }

                text = textValue

                // Set font size and weight based on slider type
                when (max) {
                    in 0..10 -> {
                        textSize = 10f // Volume: normal size
                        setTypeface(Typeface.DEFAULT_BOLD) // Extra bold
                    }
                    100 -> {
                        textSize = 9f // Elevation: smaller size
                        setTypeface(Typeface.DEFAULT_BOLD) // Extra bold
                    }
                    200 -> {
                        textSize = 9f // QNH: smaller size
                        setTypeface(Typeface.DEFAULT_BOLD) // Extra bold
                    }
                    else -> {
                        textSize = 10f
                        setTypeface(Typeface.DEFAULT_BOLD)
                    }
                }

                setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            }

            val params = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            tickView.layoutParams = params
            tickView.gravity = android.view.Gravity.CENTER_HORIZONTAL
            tickContainer.addView(tickView)
        }
    }

    // Delegate methods to the underlying SeekBar
    fun setOnSeekBarChangeListener(listener: SeekBar.OnSeekBarChangeListener) {
        seekBar.setOnSeekBarChangeListener(listener)
    }

    fun getProgress(): Int = seekBar.progress
    fun setProgress(progress: Int) {
        seekBar.progress = progress
    }

    fun getMax(): Int = seekBar.max
    fun setMax(max: Int) {
        maxValue = max
        seekBar.max = max
        setupTickMarks(max)
    }
}