package com.nyqova.app.library

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.nyqova.app.MainActivity

/** Retain the old internal entry point for upgrades; all browsing now uses the TV shell. */
class LibraryActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
