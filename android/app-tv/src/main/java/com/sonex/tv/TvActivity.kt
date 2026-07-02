package com.sonex.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * TV UI: big pairing code + connection status. Runs the TvServer that the phone
 * discovers and pairs with over the same Wi-Fi.
 */
class TvActivity : ComponentActivity() {
    private lateinit var server: TvServer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var code by remember { mutableStateOf("----") }
            var status by remember { mutableStateOf("Waiting for your phone…") }

            LaunchedEffect(Unit) {
                server = TvServer(this@TvActivity, onCode = { code = it }, onStatus = { status = it })
                server.start()
            }

            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF7C4DFF))) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF0E0B1A)) {
                    Column(
                        Modifier.fillMaxSize().padding(64.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("SoNex TV", fontSize = 44.sp, fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp))
                        Text("Open SoNex on your phone and enter this code",
                            fontSize = 20.sp, color = Color(0xFFB9B3CC))
                        Spacer(Modifier.height(40.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            code.forEach { c ->
                                Surface(
                                    color = Color(0xFF1E1836),
                                    shape = MaterialTheme.shapes.large,
                                    modifier = Modifier.size(120.dp, 150.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(c.toString(), fontSize = 72.sp,
                                            fontWeight = FontWeight.Black, color = Color.White)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(48.dp))
                        Text(status, fontSize = 18.sp, color = Color(0xFF2DD4BF))
                    }
                }
            }
        }
    }

    override fun onDestroy() { if (::server.isInitialized) server.stop(); super.onDestroy() }
}
