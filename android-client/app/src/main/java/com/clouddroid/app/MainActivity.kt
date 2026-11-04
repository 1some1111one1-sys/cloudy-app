package com.clouddroid.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.clouddroid.app.ui.AppRoot
import com.clouddroid.app.ui.CloudDroidTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CloudDroidTheme { AppRoot() } }
    }
}
