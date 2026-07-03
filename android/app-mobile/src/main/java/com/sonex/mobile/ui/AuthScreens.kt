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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material.icons.filled.MarkEmailRead
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
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
    var info by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var awaitingOtp by remember { mutableStateOf(false) }
    var otp by remember { mutableStateOf("") }
    var showForgot by remember { mutableStateOf(false) }
    var agreed by remember { mutableStateOf(false) }

    val base = (Prefs.serverUrl(ctx) ?: "").removeSuffix("/")

    fun succeed(token: String) {
        Prefs.setAuthToken(ctx, token)
        Prefs.setAccountEmail(ctx, email)
        Prefs.setLoggedIn(ctx, true)
        // Same login on web + phone => same settings, instantly.
        scope.launch { com.sonex.mobile.data.ServerSync.pullSettings(ctx) }
        onLoggedIn()
    }

    fun submit() {
        error = AuthValidator.emailError(email) ?: AuthValidator.passwordError(password)
            ?: if (isSignup && confirm != password) "Passwords don't match"
            else if (isSignup && !agreed) "Please agree to the Terms & Privacy policy to continue"
            else null
        if (error != null) return
        if (base.isBlank()) { error = "Can't connect right now — try again"; return }
        working = true
        scope.launch {
            val res = if (isSignup) AuthApi.signup(base, email.trim(), password)
                      else AuthApi.login(base, email.trim(), password)
            working = false
            when (res) {
                is AuthApi.Result.Success -> succeed(res.token)
                is AuthApi.Result.Info -> { awaitingOtp = true; info = res.message; error = null }
                is AuthApi.Result.Failure ->
                    // Unverified account: the server just re-sent a code.
                    if (res.message.contains("verified", ignoreCase = true)) {
                        awaitingOtp = true; info = res.message; error = null
                    } else error = res.message
            }
        }
    }

    fun verifyOtp() {
        if (otp.length != 6) { error = "Enter the 6-digit code from your email"; return }
        working = true
        scope.launch {
            when (val res = AuthApi.verify(base, email.trim(), otp)) {
                is AuthApi.Result.Success -> succeed(res.token)
                is AuthApi.Result.Info -> info = res.message
                is AuthApi.Result.Failure -> error = res.message
            }
            working = false
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
            SonexLogo(fontSize = 56.sp)
            Spacer(Modifier.height(40.dp))

            AnimatedVisibility(true, enter = fadeIn() + slideInVertically { it / 3 }) {
                // Swipe left/right anywhere on the form to flip Sign in <-> Sign up.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.pointerInput(Unit) {
                        detectHorizontalDragGestures { _, dragAmount ->
                            if (dragAmount < -40f) { isSignup = true; error = null }
                            if (dragAmount > 40f) { isSignup = false; error = null }
                        }
                    }
                ) {
                    if (awaitingOtp) {
                        // ---- Step 2: the 6-digit code from the email ----
                        Icon(Icons.Filled.MarkEmailRead, null,
                            Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(10.dp))
                        Text("Check your email", style = MaterialTheme.typography.titleLarge)
                        Text(
                            info ?: "We sent a 6-digit code to $email",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(18.dp))
                        OutlinedTextField(
                            otp, { if (it.length <= 6 && it.all(Char::isDigit)) { otp = it; error = null } },
                            label = { Text("6-digit code") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                        )
                        error?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                        }
                        Spacer(Modifier.height(18.dp))
                        Button(
                            onClick = ::verifyOtp,
                            enabled = otp.length == 6 && !working,
                            modifier = Modifier.fillMaxWidth().height(54.dp)
                        ) {
                            if (working) CircularProgressIndicator(Modifier.size(22.dp))
                            else Text("Verify", style = MaterialTheme.typography.labelLarge)
                        }
                        TextButton(onClick = {
                            scope.launch {
                                info = "Sending a new code…"
                                val r = AuthApi.signup(base, email.trim(), password)
                                info = when (r) {
                                    is AuthApi.Result.Info -> r.message
                                    is AuthApi.Result.Failure -> r.message
                                    else -> info
                                }
                            }
                        }) { Text("Resend code") }
                        TextButton(onClick = { awaitingOtp = false; otp = ""; error = null }) {
                            Text("Back")
                        }
                    } else {
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
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(
                                checked = agreed,
                                onCheckedChange = { agreed = it; error = null },
                                modifier = Modifier.clip(androidx.compose.foundation.shape.CircleShape)
                            )
                            val uri = androidx.compose.ui.platform.LocalUriHandler.current
                            Text(
                                buildAnnotatedString {
                                    append("I agree to the ")
                                    pushStyle(SpanStyle(color = MaterialTheme.colorScheme.primary,
                                        textDecoration = TextDecoration.Underline))
                                    append("Terms & Privacy"); pop()
                                    append(" (audio is used only to improve SoNex and deleted after training)")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.clickable {
                                    runCatching { uri.openUri("${base.ifBlank { Prefs.DEFAULT_SERVER }}/privacy") }
                                }
                            )
                        }
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
                    if (!isSignup) {
                        TextButton(onClick = { showForgot = true }) {
                            Icon(Icons.Filled.LockReset, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Forgot password?")
                        }
                    }
                    }
                }
            }
        }
        Text(
            "A product of Patience AI",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 18.dp)
        )
        if (showForgot) {
            PasswordResetDialog(
                initialEmail = email,
                onDismiss = { showForgot = false },
                onDone = { msg ->
                    showForgot = false
                    isSignup = false
                    error = null; info = msg
                    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
                }
            )
        }
    }
}

/**
 * Two-step reset used by "Forgot password?" and Settings -> Change password:
 * email -> Brevo code -> new password. The server tears down every session.
 */
@Composable
fun PasswordResetDialog(initialEmail: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val base = (Prefs.serverUrl(ctx) ?: "").removeSuffix("/")
    var step by remember { mutableStateOf(0) } // 0 email, 1 code + new password
    var email by remember { mutableStateOf(initialEmail) }
    var code by remember { mutableStateOf("") }
    var newPw by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(if (step == 0) "Reset password" else "Enter the code") },
        text = {
            Column {
                if (step == 0) {
                    Text("We'll email you a 6-digit code.")
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        email, { email = it; msg = null }, label = { Text("Email") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                    )
                } else {
                    OutlinedTextField(
                        code, { if (it.length <= 6 && it.all(Char::isDigit)) { code = it; msg = null } },
                        label = { Text("6-digit code") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    Spacer(Modifier.height(10.dp))
                    PasswordField(newPw, { newPw = it; msg = null }, "New password")
                }
                msg?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !working &&
                    (if (step == 0) AuthValidator.emailError(email) == null
                     else code.length == 6 && AuthValidator.passwordError(newPw) == null),
                onClick = {
                    working = true
                    scope.launch {
                        if (step == 0) {
                            when (val r = AuthApi.forgot(base, email.trim())) {
                                is AuthApi.Result.Info -> { step = 1; msg = null }
                                is AuthApi.Result.Failure -> msg = r.message
                                else -> {}
                            }
                        } else {
                            when (val r = AuthApi.reset(base, email.trim(), code, newPw)) {
                                is AuthApi.Result.Info -> onDone(r.message)
                                is AuthApi.Result.Failure -> msg = r.message
                                else -> {}
                            }
                        }
                        working = false
                    }
                }
            ) {
                if (working) CircularProgressIndicator(Modifier.size(18.dp))
                else Text(if (step == 0) "Send code" else "Change password")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Cancel") } }
    )
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
