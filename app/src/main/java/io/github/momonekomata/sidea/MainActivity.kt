package io.github.momonekomata.sidea

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import io.github.momonekomata.sidea.ui.SideAScreen
import io.github.momonekomata.sidea.ui.SideATheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(vm::openLocal)
    }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 && savedInstanceState == null) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            SideATheme {
                SideAScreen(vm, pickFolder = { pickFolder.launch(null) })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }
}
