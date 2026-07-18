package io.github.rufim.alice.launcher

import io.github.rufim.alice.R
import io.github.rufim.alice.NativeBridge
import io.github.rufim.alice.tts.*
import io.github.rufim.alice.cheats.*
import io.github.rufim.alice.launcher.*
import io.github.rufim.alice.engine.*
import io.github.rufim.alice.ui.*

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import java.io.BufferedReader

class LicensesActivity : Activity() {
    companion object {
        const val EXTRA_DISPLAY_NAME = "DISPLAY_NAME"
        const val EXTRA_FILE_NAME = "FILE_NAME"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_licenses)

        actionBar?.title = intent.getStringExtra(EXTRA_DISPLAY_NAME)
        val path = "licenses/" + intent.getStringExtra(EXTRA_FILE_NAME)
        val text = assets.open(path).bufferedReader().use(BufferedReader::readText)
        findViewById<TextView>(R.id.license_text).text = text
    }
}