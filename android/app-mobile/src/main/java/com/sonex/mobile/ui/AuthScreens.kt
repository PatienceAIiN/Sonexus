package com.sonex.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonex.mobile.data.AuthApi
import com.sonex.mobile.data.AuthValidator
import com.sonex.mobile.data.Prefs
import com.sonex.mobile.pairing.PairingClient
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(onLoggedIn: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var isSignup by remember { mutableStateOf(!Prefs.hasAccount(ctx)) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    fun submit() {
        error = AuthValidator.emailError(email) ?: AuthValidator.passwordError(password)
            ?: if (isSignup && confirm != password) "Passwords don't match" else null
        if (error != null) return
        // Accounts live on the SoNex server (URL baked in, overridable in Settings).
        val base = (Prefs.serverUrl(ctx) ?: "").removeSuffix("/")
        if (base.isBlank()) {
            error = "No server configured — set the server URL in Settings"
            return
        }
        working = true
        scope.launch {
            val res = if (isSignup) AuthApi.signup(base, email.trim(), password)
                      else AuthApi.login(base, email.trim(), password)
            working = false
            when (res) {
                is AuthApi.Result.Success -> {
                    Prefs.setAuthToken(ctx, res.token)
                    Prefs.setLoggedIn(ctx, true)
                    onLoggedIn()
                }
                is AuthApi.Result.Failure -> error = res.message
            }
        }
    }

    // Signature: a slow-breathing violet aura behind the wordmark.
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(
        1f, 1.12f,
        infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "s"
    )

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.surface)
            )
        )
    ) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "SoNex", fontSize = 56.sp, fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.scale(scale)
            )
            Text(
                "Volume that listens to the room",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(40.dp))

            AnimatedVisibility(true, enter = fadeIn() + slideInVertically { it / 3 }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TabRow(selectedTabIndex = if (isSignup) 1 else 0) {
                        Tab(!isSignup, onClick = { isSignup = false; error = null }, text = { Text("Sign in") })
                        Tab(isSignup, onClick = { isSignup = true; error = null }, text = { Text("Sign up") })
                    }
                    Spacer(Modifier.height(20.dp))
                    OutlinedTextField(
                        email, { email = it; error = null }, label = { Text("Email") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                    )
                    Spacer(Modifier.height(12.dp))
                    PasswordField(password, { password = it; error = null }, "Password")
                    if (isSignup) {
                        Spacer(Modifier.height(12.dp))
                        PasswordField(confirm, { confirm = it; error = null }, "Confirm password")
                    }
                    error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center)
                    }
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = ::submit,
                        enabled = email.isNotBlank() && password.isNotBlank() && !working,
                        modifier = Modifier.fillMaxWidth().height(54.dp)
                    ) {
                        if (working) CircularProgressIndicator(Modifier.size(22.dp))
                        else Text(
                            if (isSignup) "Create account" else "Sign in",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}

/** Password input with a show/hide eye toggle. */
@Composable
fun PasswordField(value: String, onChange: (String) -> Unit, label: String) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value, onChange, label = { Text(label) },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "Hide password" else "Show password"
                )
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairScreen(onPaired: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { PairingClient(ctx) }
    var code by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Looking for your TV on this Wi-Fi…") }
    var found by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        found = client.discover()
        status = if (found) "TV found. Enter the 4-digit code shown on your TV."
                 else "No SoNex TV found. Make sure both are on the same Wi-Fi."
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Pair TV") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
            }
        )
    }) { pad ->
    Column(
        Modifier.fillMaxSize().padding(pad).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Pair your TV", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            status, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))

        // Four big digit boxes.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(4) { i ->
                val ch = code.getOrNull(i)?.toString() ?: ""
                Surface(
                    tonalElevation = 3.dp,
                    color = if (ch.isEmpty()) MaterialTheme.colorScheme.surfaceVariant
                            else MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.size(64.dp, 76.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(ch, fontSize = 30.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            code, { if (it.length <= 4 && it.all(Char::isDigit)) code = it },
            label = { Text("Enter code") }, singleLine = true, enabled = found,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
        )
        Spacer(Modifier.height(24.dp))
        Button(
            enabled = found && code.length == 4 && !working,
            onClick = {
                working = true
                scope.launch {
                    val res = client.pair(code, "My Phone")
                    working = false
                    if (res.ok) onPaired(res.tvName)
                    else status = res.error.ifBlank { "Wrong code, try again." }
                }
            },
            modifier = Modifier.fillMaxWidth().height(54.dp)
        ) { if (working) CircularProgressIndicator(Modifier.size(22.dp)) else Text("Connect") }
    }
    }
}
