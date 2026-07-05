package com.project.smartattendance

import android.Manifest
import android.app.DatePickerDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Patterns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SmartAttendanceApp() }
    }
}

private enum class AuthTab(val label: String) {
    Login("Login"),
    SignUp("Sign Up")
}

private enum class SignupStep(val title: String) {
    Details("Profile"),
    Face("Face Enroll"),
    Review("Review")
}

private enum class CameraCaptureMode {
    SignupEnroll,
    Enroll,
    Verify
}

private enum class TeacherSection(val label: String) {
    Live("Live Session"),
    Sessions("History"),
    Attendance("Attendance"),
    Profile("Profile")
}

private enum class TeacherAttendancePage(val label: String) {
    LiveRoster("Live Roster"),
    Process("Attendance Process"),
    MarkedStudents("Marked Students"),
    StudentReport("Student Report")
}

private enum class StudentSection(val label: String) {
    Home("Home"),
    MarkAttendance("Mark Attendance"),
    History("History"),
    Profile("Profile")
}

private enum class AttendanceFlowStep {
    ClassSelection,
    OtpValidation,
    AutoVerification,
    Result
}

private enum class VerificationUiState {
    Pending,
    Loading,
    Success,
    Error
}

private val smartAttendanceColorScheme = lightColorScheme(
    primary = Color(0xFF163E82),
    onPrimary = Color.White,
    secondary = Color(0xFF117A65),
    onSecondary = Color.White,
    tertiary = Color(0xFF7D5200),
    background = Color(0xFFF5F7FB),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF16263D)
)

private val smartAttendanceTypography = Typography()

@Composable
private fun SmartAttendanceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = smartAttendanceColorScheme,
        typography = smartAttendanceTypography,
        content = content
    )
}

private fun isValidEmailInput(email: String): Boolean {
    return Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
}

@Composable
fun SmartAttendanceApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentSession by remember { mutableStateOf<AuthSession?>(null) }
    var currentProfile by remember { mutableStateOf<UserProfile?>(null) }
    var isBootstrapping by remember { mutableStateOf(true) }
    var authStatus by remember { mutableStateOf("Checking saved login...") }
    var isAuthBusy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val savedSession = SessionStore.load(context)
        if (savedSession == null) {
            isBootstrapping = false
            authStatus = "Create an account or log in to continue."
        } else {
            val result = withContext(Dispatchers.IO) { restoreUserSession(savedSession) }
            if (result.success && result.session != null && result.profile != null) {
                currentSession = result.session
                currentProfile = result.profile
                SessionStore.save(context, result.session)
            } else {
                SessionStore.clear(context)
                authStatus = result.message
            }
            isBootstrapping = false
        }
    }

    SmartAttendanceTheme {
        AppContainer {
            when {
                isBootstrapping -> LoadingScreen(authStatus)
                currentSession == null || currentProfile == null -> AuthScreen(
                    statusText = authStatus,
                    isBusy = isAuthBusy,
                    onLogin = { email, password ->
                        if (isAuthBusy) return@AuthScreen
                        scope.launch {
                            isAuthBusy = true
                            authStatus = "Logging in..."
                            val result = withContext(Dispatchers.IO) { signInUser(email, password) }
                            if (result.success && result.session != null && result.profile != null) {
                                authStatus = result.message
                                currentSession = result.session
                                currentProfile = result.profile
                                SessionStore.save(context, result.session)
                            } else {
                                authStatus = result.message
                            }
                            isAuthBusy = false
                        }
                    },
                    onSignUp = { name, roll, email, password, role, faceTemplate, faceImageBase64 ->
                        if (isAuthBusy) return@AuthScreen
                        scope.launch {
                            isAuthBusy = true
                            authStatus = "Creating account..."
                            val result = withContext(Dispatchers.IO) {
                                signUpUser(
                                    email = email,
                                    password = password,
                                    fullName = name,
                                    rollNumber = roll,
                                    role = role,
                                    faceTemplate = faceTemplate,
                                    faceImageBase64 = faceImageBase64
                                )
                            }
                            if (result.success && result.session != null && result.profile != null) {
                                currentSession = result.session
                                currentProfile = result.profile
                                SessionStore.save(context, result.session)
                                authStatus = result.message
                            } else {
                                authStatus = result.message
                            }
                            isAuthBusy = false
                        }
                    }
                )
                currentProfile?.role == UserRole.Teacher && currentSession != null && currentProfile != null -> TeacherDashboard(
                    session = currentSession ?: return@AppContainer,
                    profile = currentProfile ?: return@AppContainer,
                    onLogout = {
                        SessionStore.clear(context)
                        currentSession = null
                        currentProfile = null
                        authStatus = "You have been logged out."
                    }
                )
                currentSession != null && currentProfile != null -> StudentDashboard(
                    session = currentSession ?: return@AppContainer,
                    profile = currentProfile ?: return@AppContainer,
                    onLogout = {
                        SessionStore.clear(context)
                        currentSession = null
                        currentProfile = null
                        authStatus = "You have been logged out."
                    }
                )
            }
        }
    }
}

@Composable
private fun AppContainer(content: @Composable () -> Unit) {
    Scaffold(containerColor = Color(0xFFF2F5F9)) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF5F7FB))
                .padding(paddingValues)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .offset(y = (-40).dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color(0xFFB9D7FF), Color.Transparent)
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .offset(x = 140.dp, y = 40.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color(0xFFFFE3A7), Color.Transparent)
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 980.dp)
                    .align(Alignment.TopCenter)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun LoadingScreen(message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, color = Color(0xFF43546D))
    }
}

@Composable
private fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    loading: Boolean = false,
    loadingText: String = text,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(56.dp),
        enabled = enabled,
        shape = RoundedCornerShape(18.dp)
    ) {
        ButtonLabel(loading, text, loadingText)
    }
}

@Composable
private fun SecondaryActionButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(56.dp),
        enabled = enabled,
        shape = RoundedCornerShape(18.dp)
    ) {
        Text(text)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardScaffold(
    title: String,
    subtitle: String,
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onLogout: () -> Unit,
    content: @Composable (Modifier) -> Unit
) {
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF607087))
                    items.forEachIndexed { index, item ->
                        NavigationDrawerItem(
                            label = { Text(item) },
                            selected = index == selectedIndex,
                            onClick = {
                                onSelect(index)
                                scope.launch { drawerState.close() }
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    SecondaryActionButton(
                        text = "Logout",
                        onClick = {
                            scope.launch { drawerState.close() }
                            onLogout()
                        }
                    )
                }
            }
        }
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = {
                    Column {
                        Text(title, fontWeight = FontWeight.Bold)
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color(0xFF5C6D86))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Text("\u2630", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        }
        ) { paddingValues ->
            content(
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState())
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudentScaffold(
    title: String,
    subtitle: String,
    selectedSection: StudentSection,
    onSelect: (StudentSection) -> Unit,
    onLogout: () -> Unit,
    snackbarHostState: SnackbarHostState? = null,
    content: @Composable (Modifier) -> Unit
) {
    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = {
            snackbarHostState?.let { SnackbarHost(hostState = it) }
        },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = {
                    Column {
                        Text(title, fontWeight = FontWeight.Bold)
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color(0xFF5C6D86))
                    }
                },
                actions = {
                    OutlinedButton(onClick = onLogout) { Text("Logout") }
                }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                NavigationBarItem(
                    selected = selectedSection == StudentSection.Home,
                    onClick = { onSelect(StudentSection.Home) },
                    icon = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_person),
                            contentDescription = "Home"
                        )
                    },
                    label = { Text("Home") }
                )
                NavigationBarItem(
                    selected = selectedSection == StudentSection.MarkAttendance,
                    onClick = { onSelect(StudentSection.MarkAttendance) },
                    icon = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_start),
                            contentDescription = "Mark Attendance",
                            modifier = Modifier.height(32.dp).width(32.dp)
                        )
                    },
                    label = {
                        Text(
                            "Mark Attendance",
                            fontWeight = if (selectedSection == StudentSection.MarkAttendance) FontWeight.SemiBold else FontWeight.Medium,
                            fontSize = 12.sp
                        )
                    }
                )
                NavigationBarItem(
                    selected = selectedSection == StudentSection.History,
                    onClick = { onSelect(StudentSection.History) },
                    icon = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_refresh),
                            contentDescription = "History"
                        )
                    },
                    label = { Text("History") }
                )
                NavigationBarItem(
                    selected = selectedSection == StudentSection.Profile,
                    onClick = { onSelect(StudentSection.Profile) },
                    icon = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_menu_24),
                            contentDescription = "Profile"
                        )
                    },
                    label = { Text("Profile") }
                )
            }
        }
    ) { paddingValues ->
        content(
            Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState())
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthScreen(
    statusText: String,
    isBusy: Boolean,
    onLogin: (String, String) -> Unit,
    onSignUp: (String, String, String, String, UserRole, String?, String?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableStateOf(AuthTab.Login) }
    var signUpRole by rememberSaveable { mutableStateOf(UserRole.Student) }
    var loginEmail by rememberSaveable { mutableStateOf("") }
    var loginPassword by rememberSaveable { mutableStateOf("") }
    var signUpName by rememberSaveable { mutableStateOf("") }
    var signUpRoll by rememberSaveable { mutableStateOf("") }
    var signUpEmail by rememberSaveable { mutableStateOf("") }
    var signUpPassword by rememberSaveable { mutableStateOf("") }
    var signUpFaceTemplate by remember { mutableStateOf<String?>(null) }
    var signUpFaceImageBase64 by remember { mutableStateOf<String?>(null) }
    var signUpFaceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isPreparingFace by remember { mutableStateOf(false) }
    var signUpFaceStatus by remember { mutableStateOf("Capture face for student registration.") }
    val snackbarHostState = remember { SnackbarHostState() }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        signUpFaceStatus = if (granted) {
            "Camera permission granted."
        } else {
            "Camera permission is required."
        }
    }

    val signUpCameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap == null) {
            signUpFaceStatus = "Face capture cancelled."
            return@rememberLauncherForActivityResult
        }

        signUpFaceBitmap = bitmap
        scope.launch {
            isPreparingFace = true
            signUpFaceStatus = "Processing the face enrollment photo..."
            val result = withTimeoutOrNull(12_000L) {
                withContext(Dispatchers.Default) { createFaceTemplate(bitmap) }
            } ?: FaceTemplateResult(false, "Face enrollment timed out. Try again.")

            if (result.success && result.template != null) {
                signUpFaceTemplate = result.template
                signUpFaceImageBase64 = bitmapToBase64(bitmap)
                signUpFaceStatus = "Face enrollment is ready for student account creation."
            } else {
                signUpFaceTemplate = null
                signUpFaceImageBase64 = null
                signUpFaceStatus = result.message
            }
            isPreparingFace = false
        }
    }

    LaunchedEffect(signUpRole) {
        if (signUpRole != UserRole.Student) {
            signUpFaceTemplate = null
            signUpFaceImageBase64 = null
            signUpFaceBitmap = null
            signUpFaceStatus = "Teacher registration does not require face capture."
        } else if (signUpFaceTemplate == null) {
            signUpFaceStatus = "Capture face for student registration."
        }
    }

    LaunchedEffect(statusText) {
        if (statusText.isNotBlank()) {
            snackbarHostState.showSnackbar(statusText)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "SmartAttendance",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (selectedTab == AuthTab.Login) {
                        "Sign in to continue."
                    } else {
                        "Create your account."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                TabRow(selectedTabIndex = selectedTab.ordinal) {
                    AuthTab.entries.forEach { tab ->
                        Tab(
                            selected = tab == selectedTab,
                            onClick = { selectedTab = tab },
                            text = { Text(tab.label) }
                        )
                    }
                }

                if (selectedTab == AuthTab.Login) {
                    OutlinedTextField(
                        value = loginEmail,
                        onValueChange = { loginEmail = it.trim() },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Email") },
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = loginPassword,
                        onValueChange = { loginPassword = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    PrimaryActionButton(
                        text = "Login",
                        loadingText = "Logging In...",
                        onClick = { onLogin(loginEmail, loginPassword) },
                        enabled = !isBusy && isValidEmailInput(loginEmail) && loginPassword.length >= 6,
                        loading = isBusy
                    )
                    OutlinedButton(
                        onClick = { selectedTab = AuthTab.SignUp },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Create New Account")
                    }
                } else {
                    Text(
                        text = "Account Type",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(
                            selected = signUpRole == UserRole.Student,
                            onClick = { signUpRole = UserRole.Student },
                            label = { Text("Student") }
                        )
                        FilterChip(
                            selected = signUpRole == UserRole.Teacher,
                            onClick = { signUpRole = UserRole.Teacher },
                            label = { Text("Teacher") }
                        )
                    }
                    OutlinedTextField(
                        value = signUpName,
                        onValueChange = { signUpName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Full name") },
                        singleLine = true
                    )
                    if (signUpRole == UserRole.Student) {
                        OutlinedTextField(
                            value = signUpRoll,
                            onValueChange = { signUpRoll = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Roll number") },
                            singleLine = true
                        )
                    }
                    OutlinedTextField(
                        value = signUpEmail,
                        onValueChange = { signUpEmail = it.trim() },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Email") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = signUpPassword,
                        onValueChange = { signUpPassword = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )

                    if (signUpRole == UserRole.Student) {
                        signUpFaceBitmap?.let { bitmap ->
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Student face enrollment preview",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                            )
                        }
                        Text(signUpFaceStatus, style = MaterialTheme.typography.bodySmall, color = Color(0xFF5F6F86))
                        SecondaryActionButton(
                            text = if (signUpFaceTemplate == null) "Capture Face" else "Capture Again",
                            onClick = {
                                if (hasCameraPermission(context)) {
                                    signUpCameraLauncher.launch(null)
                                } else {
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            },
                            enabled = !isPreparingFace
                        )
                    }

                    PrimaryActionButton(
                        text = "Create Account",
                        loadingText = "Creating...",
                        onClick = {
                            onSignUp(
                                signUpName,
                                signUpRoll,
                                signUpEmail,
                                signUpPassword,
                                signUpRole,
                                signUpFaceTemplate,
                                signUpFaceImageBase64
                            )
                        },
                        enabled = !isBusy &&
                            signUpName.isNotBlank() &&
                            isValidEmailInput(signUpEmail) &&
                            signUpPassword.length >= 6 &&
                            (signUpRole == UserRole.Teacher || signUpRoll.isNotBlank()) &&
                            (signUpRole == UserRole.Teacher || signUpFaceTemplate != null),
                        loading = isBusy
                    )
                    OutlinedButton(
                        onClick = { selectedTab = AuthTab.Login },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Back To Login")
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }
}

@Composable
private fun DashboardSectionTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF9FBFE))
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            labels.forEachIndexed { index, label ->
                val isSelected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .background(
                            if (isSelected) Color(0xFF163E82) else Color.White,
                            RoundedCornerShape(18.dp)
                        )
                        .clickable { onSelect(index) }
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        color = if (isSelected) Color.White else Color(0xFF43546D),
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TeacherDashboard(
    session: AuthSession,
    profile: UserProfile,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val broadcaster = remember(context) { TeacherBleBroadcaster(context.applicationContext) }
    val sessions = remember { mutableStateListOf<Session>() }
    val attendanceRecords = remember { mutableStateListOf<AttendanceRecord>() }

    var selectedSection by rememberSaveable { mutableStateOf(TeacherSection.Live) }
    var attendancePage by rememberSaveable { mutableStateOf(TeacherAttendancePage.Process) }
    var selectedTeacherDate by remember { mutableStateOf<LocalDate?>(LocalDate.now()) }
    var selectedTeacherPeriod by remember { mutableStateOf<Int?>(1) }
    var selectedReportStudentKey by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedReportDays by rememberSaveable { mutableStateOf(7) }
    var pendingClassDate by remember { mutableStateOf(LocalDate.now()) }
    var pendingClassPeriod by remember { mutableStateOf<Int?>(1) }
    var activeSession by remember { mutableStateOf<Session?>(null) }
    var remainingMillis by remember { mutableLongStateOf(0L) }
    var bleRefreshToken by remember { mutableLongStateOf(0L) }
    var statusText by remember { mutableStateOf("Ready, ${profile.fullName}") }
    var bleStatus by remember { mutableStateOf("No active Bluetooth broadcast.") }
    var teacherBleDetails by remember { mutableStateOf(teacherBleDiagnostics(context).details) }
    var isCreatingSession by remember { mutableStateOf(false) }
    var isLoadingHistory by remember { mutableStateOf(true) }
    var isLoadingAttendance by remember { mutableStateOf(true) }
    var pendingStartSession by remember { mutableStateOf(false) }
    var sessionWasEnded by remember { mutableStateOf(false) }
    var showSecurityInfo by remember { mutableStateOf(false) }

    fun hasTeacherBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun isTeacherBluetoothEnabled(): Boolean {
        val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
        return bluetoothManager?.adapter?.isEnabled == true
    }

    fun createTeacherSession() {
        scope.launch {
            isCreatingSession = true
            statusText = "Starting session..."
            val result = withTimeoutOrNull(10_000L) {
                withContext(Dispatchers.IO) {
                    saveSessionDetailed(
                        code = generateCode(),
                        teacherProfile = profile,
                        accessToken = session.accessToken,
                        classDate = localDateToStorageString(pendingClassDate),
                        classPeriod = pendingClassPeriod ?: 1,
                        expiresAt = nowMillis() + SESSION_DURATION_MILLIS
                    )
                }
            } ?: SessionSaveResult(message = "Session creation timed out. Check your backend connection.")
            statusText = result.message
            if (result.session != null) {
                sessionWasEnded = false
                activeSession = result.session
                selectedTeacherDate = result.session.slotDate()
                selectedTeacherPeriod = result.session.classPeriod
                bleRefreshToken = nowMillis()
                val loadedSessions = withContext(Dispatchers.IO) { loadTeacherSessions(session.accessToken, profile) }
                sessions.clear()
                sessions.addAll(loadedSessions)
                val refreshedActiveSession = loadedSessions.firstOrNull { it.isActive && it.expiresAt > nowMillis() }
                activeSession = refreshedActiveSession
                if (refreshedActiveSession != null) {
                    selectedTeacherDate = refreshedActiveSession.slotDate()
                    selectedTeacherPeriod = refreshedActiveSession.classPeriod ?: selectedTeacherPeriod ?: pendingClassPeriod
                }
                val loadedAttendance = withContext(Dispatchers.IO) { loadTeacherAttendance(session.accessToken, profile) }
                attendanceRecords.clear()
                attendanceRecords.addAll(loadedAttendance)
            }
            isCreatingSession = false
            pendingStartSession = false
        }
    }

    val bluetoothEnableLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        teacherBleDetails = teacherBleDiagnostics(context).details
        bleRefreshToken = nowMillis()
        if (pendingStartSession) {
            if (isTeacherBluetoothEnabled()) {
                createTeacherSession()
            } else {
                pendingStartSession = false
                isCreatingSession = false
                statusText = "Bluetooth was not enabled. Please retry."
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val hasPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            grants[Manifest.permission.BLUETOOTH_ADVERTISE] == true &&
                grants[Manifest.permission.BLUETOOTH_CONNECT] == true
        } else {
            true
        }

        bleStatus = if (hasPermissions) {
            "Teacher Bluetooth permissions granted."
        } else {
            "Teacher Bluetooth broadcast needs advertise/connect permissions."
        }
        teacherBleDetails = teacherBleDiagnostics(context).details
        if (hasPermissions) {
            bleRefreshToken = nowMillis()
            if (pendingStartSession) {
                if (!isTeacherBluetoothEnabled()) {
                    statusText = "Enable Bluetooth to start session."
                    bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                } else {
                    createTeacherSession()
                }
            }
        } else if (pendingStartSession) {
            pendingStartSession = false
            isCreatingSession = false
            statusText = "Bluetooth permission denied. Please allow it to start session."
        }
    }

    fun refreshTeacherHistory() {
        scope.launch {
            isLoadingHistory = true
            val result = withContext(Dispatchers.IO) { loadTeacherSessions(session.accessToken, profile) }
            sessions.clear()
            sessions.addAll(result)
            val refreshedActiveSession = result.firstOrNull { it.isActive && it.expiresAt > nowMillis() }
            activeSession = refreshedActiveSession
            if (refreshedActiveSession != null) {
                selectedTeacherDate = refreshedActiveSession.slotDate()
                selectedTeacherPeriod = refreshedActiveSession.classPeriod ?: selectedTeacherPeriod ?: pendingClassPeriod
            } else if (selectedTeacherPeriod == null) {
                selectedTeacherPeriod = pendingClassPeriod
            }
            isLoadingHistory = false
        }
    }

    fun refreshTeacherAttendance(showLoading: Boolean = true) {
        scope.launch {
            if (showLoading) {
                isLoadingAttendance = true
            }
            val result = withContext(Dispatchers.IO) { loadTeacherAttendance(session.accessToken, profile) }
            attendanceRecords.clear()
            attendanceRecords.addAll(result)
            isLoadingAttendance = false
        }
    }

    LaunchedEffect(profile.id) {
        refreshTeacherHistory()
        refreshTeacherAttendance()
    }

    LaunchedEffect(activeSession?.id) {
        if (activeSession != null) {
            attendancePage = TeacherAttendancePage.LiveRoster
        } else if (attendancePage == TeacherAttendancePage.LiveRoster) {
            attendancePage = TeacherAttendancePage.Process
        }
    }

    DisposableEffect(Unit) {
        onDispose { broadcaster.stop() }
    }

    LaunchedEffect(activeSession?.id, activeSession?.expiresAt) {
        val current = activeSession ?: run {
            remainingMillis = 0L
            return@LaunchedEffect
        }

        while (current.expiresAt > nowMillis()) {
            remainingMillis = current.expiresAt - nowMillis()
            delay(1000L)
        }

        remainingMillis = 0L
        withContext(Dispatchers.IO) { expireSession(current, session.accessToken) }
        statusText = "The active attendance session expired."
        sessionWasEnded = true
        pendingStartSession = false
        refreshTeacherHistory()
    }

    LaunchedEffect(activeSession?.code, bleRefreshToken) {
        val currentCode = activeSession?.code
        if (currentCode.isNullOrBlank()) {
            broadcaster.stop()
            bleStatus = "No active Bluetooth broadcast."
        } else {
            teacherBleDetails = teacherBleDiagnostics(context).details
            val result = broadcaster.start(currentCode)
            bleStatus = result.message
            teacherBleDetails = teacherBleDiagnostics(context).details
        }
    }

    LaunchedEffect(activeSession?.id, activeSession?.expiresAt) {
        val current = activeSession ?: return@LaunchedEffect
        while (current.id == activeSession?.id && current.expiresAt > nowMillis()) {
            refreshTeacherAttendance(showLoading = false)
            delay(4_000L)
        }
    }

    val activeSlotKey = activeSession?.let { AttendanceSlotKey(it.slotDate(), it.classPeriod) }
    val activeAttendanceRecords = activeSlotKey?.let { slotKey ->
        attendanceRecords.filter { AttendanceSlotKey(it.slotDate(), it.classPeriod) == slotKey }
    } ?: emptyList()
    val historySummaries = buildSlotSummaries(
        sessions = sessions.filter { !it.isActive || it.expiresAt <= nowMillis() },
        attendanceRecords = attendanceRecords.filter {
            AttendanceSlotKey(it.slotDate(), it.classPeriod) != activeSlotKey
        }
    ).filter { it.studentCount > 0 || it.sessionCount > 0 }
    val selectedAttendanceRecords = if (selectedTeacherDate != null && selectedTeacherPeriod != null) {
        attendanceRecords.filter { it.slotDate() == selectedTeacherDate && it.classPeriod == selectedTeacherPeriod }
    } else {
        emptyList()
    }
    val selectedAttendanceSession = if (selectedTeacherDate != null && selectedTeacherPeriod != null) {
        sessions
            .filter { it.slotDate() == selectedTeacherDate && it.classPeriod == selectedTeacherPeriod }
            .maxByOrNull { it.createdAt }
    } else {
        null
    }
    val reportStudentOptions = attendanceRecords
        .map { record ->
            val key = record.studentId?.takeIf { it.isNotBlank() }
                ?: listOf(record.studentName.trim(), record.studentRollNumber.orEmpty().trim()).joinToString("|")
            val label = buildString {
                append(record.studentName)
                record.studentRollNumber?.takeIf { it.isNotBlank() }?.let {
                    append(" - ")
                    append(it)
                }
            }
            TeacherStudentOption(key = key, label = label)
        }
        .distinctBy { it.key }
        .sortedBy { it.label.lowercase() }
    val selectedReportStudent = reportStudentOptions.firstOrNull { it.key == selectedReportStudentKey }
    val selectedStudentReportRows = selectedReportStudent?.let { option ->
        buildStudentAttendanceReportRows(
            sessions = sessions,
            attendanceRecords = attendanceRecords,
            studentKey = option.key,
            daysBack = selectedReportDays
        )
    }.orEmpty()

    LaunchedEffect(reportStudentOptions) {
        if (reportStudentOptions.isNotEmpty() && reportStudentOptions.none { it.key == selectedReportStudentKey }) {
            selectedReportStudentKey = reportStudentOptions.first().key
        }
    }

    val attendancePages = buildList {
        if (activeSession != null) add(TeacherAttendancePage.LiveRoster)
        add(TeacherAttendancePage.Process)
        add(TeacherAttendancePage.MarkedStudents)
        add(TeacherAttendancePage.StudentReport)
    }
    DashboardScaffold(
        title = "Teacher Dashboard",
        subtitle = profile.fullName,
        items = TeacherSection.entries.map { it.label },
        selectedIndex = selectedSection.ordinal,
        onSelect = { selectedSection = TeacherSection.entries[it] },
        onLogout = onLogout
    ) { pageModifier ->
        Column(modifier = pageModifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (selectedSection) {
                TeacherSection.Live -> {
                    val liveSession = activeSession
                    HeaderCard("Live Session Control", "One-tap class attendance control")
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            when {
                                isCreatingSession || pendingStartSession -> {
                                    Text("Starting session...", fontWeight = FontWeight.Bold, color = Color(0xFF163E82))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text("Preparing Bluetooth and generating OTP.")
                                    }
                                    Text(statusText, color = Color(0xFF4D5B73))
                                }

                                liveSession != null -> {
                                    Text("Session Live", fontWeight = FontWeight.Bold, color = Color(0xFF117A65))
                                    Text(
                                        buildString {
                                            append(liveSession.classDate?.let(::parseStoredLocalDate)?.let(::formatCalendarDate) ?: "--")
                                            append(" | ")
                                            append(classPeriodLabel(liveSession.classPeriod))
                                        },
                                        color = Color(0xFF4D5B73)
                                    )
                                    Text(
                                        liveSession.code,
                                        fontSize = 40.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF163E82)
                                    )
                                    Text("Time Left: ${formatCountdown(remainingMillis)}", color = Color(0xFF4D5B73))
                                    Text("Bluetooth: Broadcasting to nearby students", color = Color(0xFF117A65))
                                    if (activeAttendanceRecords.isNotEmpty()) {
                                        Text("Live Count: ${activeAttendanceRecords.size} students marked", color = Color(0xFF4D5B73))
                                    }
                                    Text(statusText, color = Color(0xFF4D5B73))
                                    PrimaryActionButton(
                                        text = "End Session",
                                        onClick = {
                                            val current = activeSession
                                            if (current != null) {
                                                scope.launch {
                                                    val expired = withContext(Dispatchers.IO) {
                                                        expireSession(current, session.accessToken)
                                                    }
                                                    if (expired) {
                                                        broadcaster.stop()
                                                        activeSession = null
                                                        remainingMillis = 0L
                                                        sessionWasEnded = true
                                                        statusText = "Session ended."
                                                        refreshTeacherHistory()
                                                    } else {
                                                        statusText = "Session could not be ended."
                                                    }
                                                }
                                            }
                                        },
                                        enabled = activeSession != null && !isCreatingSession
                                    )
                                }

                                sessionWasEnded -> {
                                    Text("Session Ended", fontWeight = FontWeight.Bold, color = Color(0xFFB00020))
                                    Text("Start a new attendance session for another class slot.", color = Color(0xFF4D5B73))
                                    PrimaryActionButton(
                                        text = "Start New Session",
                                        loadingText = "Starting...",
                                        onClick = {
                                            pendingStartSession = true
                                            sessionWasEnded = false
                                            if (!hasTeacherBluetoothPermissions()) {
                                                statusText = "Bluetooth permission is required to start session."
                                                permissionLauncher.launch(teacherRequiredPermissions())
                                            } else if (!isTeacherBluetoothEnabled()) {
                                                statusText = "Enable Bluetooth to start session."
                                                bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                                            } else {
                                                createTeacherSession()
                                            }
                                        },
                                        enabled = pendingClassPeriod != null && !isCreatingSession,
                                        loading = isCreatingSession || pendingStartSession
                                    )
                                }

                                else -> {
                                    DateFilterControls(
                                        title = "Class Date",
                                        selectedDate = pendingClassDate,
                                        onDateSelected = { pendingClassDate = it },
                                        onClearDate = { pendingClassDate = LocalDate.now() },
                                        emptyText = "Select class date"
                                    )
                                    PeriodFilterControls(
                                        title = "Class Period",
                                        selectedPeriod = pendingClassPeriod,
                                        onPeriodSelected = { pendingClassPeriod = it },
                                        allowAllOption = false,
                                        emptySelectionLabel = "Select period"
                                    )
                                    PrimaryActionButton(
                                        text = "Start Session",
                                        loadingText = "Starting...",
                                        onClick = {
                                            pendingStartSession = true
                                            sessionWasEnded = false
                                            if (!hasTeacherBluetoothPermissions()) {
                                                statusText = "Bluetooth permission is required to start session."
                                                permissionLauncher.launch(teacherRequiredPermissions())
                                            } else if (!isTeacherBluetoothEnabled()) {
                                                statusText = "Enable Bluetooth to start session."
                                                bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                                            } else {
                                                createTeacherSession()
                                            }
                                        },
                                        enabled = pendingClassPeriod != null && !isCreatingSession,
                                        loading = isCreatingSession || pendingStartSession
                                    )
                                }
                            }
                        }
                    }
                    if (liveSession != null) {
                        HistoryCard(
                            title = "Live Students Marked",
                            isLoading = isLoadingAttendance,
                            isEmpty = activeAttendanceRecords.isEmpty(),
                            emptyText = "No students have marked attendance for this ongoing session yet."
                        ) {
                            activeAttendanceRecords
                                .sortedByDescending { it.markedAt }
                                .forEach { record ->
                                    HistoryRow(
                                        title = buildString {
                                            append(record.studentName)
                                            record.studentRollNumber?.let {
                                                append(" - ")
                                                append(it)
                                            }
                                        },
                                        subtitle = buildString {
                                            append(formatDateTime(record.markedAt))
                                            append(" - ")
                                            append(
                                                if (record.faceVerified && record.bluetoothVerified) {
                                                    "Present with full verification"
                                                } else {
                                                    "Present, verification needs review"
                                                }
                                            )
                                        }
                                    )
                                }
                        }
                    }
                    TextButton(onClick = { showSecurityInfo = !showSecurityInfo }) {
                        Text(if (showSecurityInfo) "Hide Session Rules" else "Session Rules")
                    }
                    if (showSecurityInfo) {
                        InfoCard(
                            "Session Rules",
                            "Use one live session per class slot. Students must scan near the teacher device. Verification validity: ${VERIFICATION_VALIDITY_MILLIS / 1000}s."
                        )
                    }
                }

                TeacherSection.Sessions -> {
                    HeaderCard("Class History", "Previous slots by date and period")
                    DateFilterControls(
                        title = "History Date",
                        selectedDate = selectedTeacherDate,
                        onDateSelected = { selectedTeacherDate = it },
                        onClearDate = { selectedTeacherDate = null }
                    )
                    PeriodFilterControls(
                        title = "History Period",
                        selectedPeriod = selectedTeacherPeriod,
                        onPeriodSelected = { selectedTeacherPeriod = it }
                    )
                    SecondaryActionButton(
                        text = if (isLoadingHistory) "Refreshing..." else "Refresh",
                        onClick = { refreshTeacherHistory() },
                        enabled = !isLoadingHistory
                    )
                    val filteredHistorySummaries = historySummaries.filter { summary ->
                        val dateMatches = selectedTeacherDate == null || summary.key.classDate == selectedTeacherDate
                        val periodMatches = selectedTeacherPeriod == null || summary.key.classPeriod == selectedTeacherPeriod
                        dateMatches && periodMatches
                    }
                    HistoryCard(
                        title = "Matching Class Slots",
                        isLoading = isLoadingHistory,
                        isEmpty = filteredHistorySummaries.isEmpty(),
                        emptyText = if (selectedTeacherDate == null && selectedTeacherPeriod == null) {
                            "No previous attendance history is available yet."
                        } else if (selectedTeacherDate != null && selectedTeacherPeriod != null) {
                            "No session history was found for ${selectedTeacherDate?.let(::formatCalendarDate) ?: "--"} ${classPeriodLabel(selectedTeacherPeriod)}."
                        } else if (selectedTeacherDate != null) {
                            "No session history was found for ${selectedTeacherDate?.let(::formatCalendarDate) ?: "--"}."
                        } else {
                            "No session history was found for ${classPeriodLabel(selectedTeacherPeriod)}."
                        }
                    ) {
                        filteredHistorySummaries.forEach { summary ->
                            SlotSummaryCard(
                                summary = summary,
                                onOpen = {
                                    selectedTeacherDate = summary.key.classDate
                                    selectedTeacherPeriod = summary.key.classPeriod
                                }
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                    RosterContextCard(
                        selectedDate = selectedTeacherDate,
                        selectedPeriod = selectedTeacherPeriod,
                        activeCode = selectedAttendanceSession?.code,
                        studentCount = selectedAttendanceRecords.size
                    )
                    if (selectedTeacherDate != null && selectedTeacherPeriod != null) {
                        MetricGrid(
                            items = listOf(
                                MetricItem("Present", selectedAttendanceRecords.size.toString(), "Students marked for this slot"),
                                MetricItem("Fully Verified", selectedAttendanceRecords.count { it.faceVerified && it.bluetoothVerified }.toString(), "Face and Bluetooth passed"),
                                MetricItem("Needs Review", selectedAttendanceRecords.count { !it.faceVerified || !it.bluetoothVerified }.toString(), "Incomplete proof"),
                                MetricItem("Session Code", selectedAttendanceSession?.code ?: "--", "Latest code used for this slot")
                            )
                        )
                    } else {
                        InfoCard(
                            "Select A Slot",
                            "Choose a date and period above to see how many students are present and open the student list."
                        )
                    }
                    HistoryCard(
                        title = "Present Students",
                        isLoading = isLoadingAttendance,
                        isEmpty = selectedTeacherDate != null && selectedTeacherPeriod != null && selectedAttendanceRecords.isEmpty(),
                        emptyText = if (selectedTeacherDate == null || selectedTeacherPeriod == null) {
                            "Select a date and period above to show the student list."
                        } else {
                            "No students are marked present for ${selectedTeacherDate?.let(::formatCalendarDate) ?: "--"} ${classPeriodLabel(selectedTeacherPeriod)}."
                        }
                    ) {
                        selectedAttendanceRecords.forEach { record ->
                            HistoryRow(
                                title = buildString {
                                    append(record.studentName)
                                    record.studentRollNumber?.let {
                                        append(" - ")
                                        append(it)
                                    }
                                },
                                subtitle = buildString {
                                    append(formatDateTime(record.markedAt))
                                    append(" - ")
                                    append(
                                        if (record.faceVerified && record.bluetoothVerified) {
                                            "Present with full verification"
                                        } else {
                                            "Present, verification needs review"
                                        }
                                    )
                                    record.sessionCode.let {
                                        append(" - Code ")
                                        append(it)
                                    }
                                }
                            )
                        }
                    }
                }

                TeacherSection.Attendance -> {
                    HeaderCard("Attendance", "Select a slot and review marked students")
                    DashboardSectionTabs(
                        labels = attendancePages.map { it.label },
                        selectedIndex = attendancePages.indexOf(attendancePage).takeIf { it >= 0 } ?: 0,
                        onSelect = { attendancePage = attendancePages[it] }
                    )

                    when (attendancePage) {
                        TeacherAttendancePage.LiveRoster -> {
                            val liveSession = activeSession
                            RosterContextCard(
                                selectedDate = liveSession?.slotDate(),
                                selectedPeriod = liveSession?.classPeriod,
                                activeCode = liveSession?.code,
                                studentCount = activeAttendanceRecords.size
                            )
                            MetricGrid(
                                items = listOf(
                                    MetricItem("Marked", activeAttendanceRecords.size.toString(), "Students in the live session"),
                                    MetricItem("Fully Verified", activeAttendanceRecords.count { it.faceVerified && it.bluetoothVerified }.toString(), "Face and Bluetooth passed"),
                                    MetricItem("Needs Review", activeAttendanceRecords.count { !it.faceVerified || !it.bluetoothVerified }.toString(), "Missing proof"),
                                    MetricItem("Time Left", if (liveSession != null) formatCountdown(remainingMillis) else "--", "Live session countdown")
                                )
                            )
                            SecondaryActionButton(
                                text = if (isLoadingAttendance) "Refreshing..." else "Refresh Live Roster",
                                onClick = { refreshTeacherAttendance() },
                                enabled = !isLoadingAttendance
                            )
                            HistoryCard(
                                title = "Students Marked In Live Session",
                                isLoading = isLoadingAttendance,
                                isEmpty = liveSession != null && activeAttendanceRecords.isEmpty(),
                                emptyText = if (liveSession == null) {
                                    "Start a session to track students live here."
                                } else {
                                    "No students have marked attendance for the current live session yet."
                                }
                            ) {
                                activeAttendanceRecords.sortedByDescending { it.markedAt }.forEach { record ->
                                    HistoryRow(
                                        title = buildString {
                                            append(record.studentName)
                                            record.studentRollNumber?.let {
                                                append(" - ")
                                                append(it)
                                            }
                                        },
                                        subtitle = buildString {
                                            append(formatDateTime(record.markedAt))
                                            append(" - ")
                                            append(if (record.faceVerified && record.bluetoothVerified) "Fully verified" else "Needs review")
                                            record.faceMatchScore?.let {
                                                append(" - Face ${(it * 100).toInt()}%")
                                            }
                                            record.bluetoothRssi?.let {
                                                append(" - ${it}dBm")
                                            }
                                        }
                                    )
                                }
                            }
                            InfoCard(
                                "Live Tracking",
                                if (liveSession == null) {
                                    "This tab appears only while a teacher session is active."
                                } else {
                                    "The list refreshes automatically while the session is running."
                                }
                            )
                        }

                        TeacherAttendancePage.Process -> {
                            DateFilterControls(
                                title = "Attendance Date",
                                selectedDate = selectedTeacherDate,
                                onDateSelected = { selectedTeacherDate = it },
                                onClearDate = { selectedTeacherDate = null }
                            )
                            PeriodFilterControls(
                                title = "Attendance Period",
                                selectedPeriod = selectedTeacherPeriod,
                                onPeriodSelected = { selectedTeacherPeriod = it }
                            )
                            RosterContextCard(
                                selectedDate = selectedTeacherDate,
                                selectedPeriod = selectedTeacherPeriod,
                                activeCode = selectedAttendanceSession?.code,
                                studentCount = selectedAttendanceRecords.size
                            )
                            SecondaryActionButton(
                                text = if (isLoadingAttendance) "Refreshing..." else "Refresh",
                                onClick = { refreshTeacherAttendance() },
                                enabled = !isLoadingAttendance
                            )
                            if (selectedTeacherDate == null || selectedTeacherPeriod == null) {
                                InfoCard(
                                    "Select A Class Slot",
                                    "Select a date and period to continue."
                                )
                            } else {
                                MetricGrid(
                                    items = listOf(
                                        MetricItem("Students", selectedAttendanceRecords.size.toString(), "Marked in this slot"),
                                        MetricItem("Fully Verified", selectedAttendanceRecords.count { it.faceVerified && it.bluetoothVerified }.toString(), "Face and Bluetooth passed"),
                                        MetricItem("Needs Review", selectedAttendanceRecords.count { !it.faceVerified || !it.bluetoothVerified }.toString(), "Incomplete proof"),
                                        MetricItem("Live Code", selectedAttendanceSession?.code ?: "--", "Current or latest code for this slot")
                                    )
                                )
                                SecondaryActionButton(
                                    text = "View Marked Students",
                                    onClick = { attendancePage = TeacherAttendancePage.MarkedStudents }
                                )
                            }
                            InfoCard(
                                "Attendance Process",
                                "Set the class date and period here, then open the marked students page."
                            )
                        }

                        TeacherAttendancePage.MarkedStudents -> {
                            RosterContextCard(
                                selectedDate = selectedTeacherDate,
                                selectedPeriod = selectedTeacherPeriod,
                                activeCode = selectedAttendanceSession?.code,
                                studentCount = selectedAttendanceRecords.size
                            )
                            SecondaryActionButton(
                                text = if (isLoadingAttendance) "Refreshing..." else "Refresh",
                                onClick = { refreshTeacherAttendance() },
                                enabled = !isLoadingAttendance
                            )
                            HistoryCard(
                                title = "Marked Students",
                                isLoading = isLoadingAttendance,
                                isEmpty = selectedTeacherDate != null && selectedTeacherPeriod != null && selectedAttendanceRecords.isEmpty(),
                                emptyText = if (selectedTeacherDate == null || selectedTeacherPeriod == null) {
                                    "Open Attendance Process first and choose a date and period."
                                } else {
                                    "No students have been marked for ${selectedTeacherDate?.let(::formatCalendarDate) ?: "--"} ${classPeriodLabel(selectedTeacherPeriod)}."
                                }
                            ) {
                                selectedAttendanceRecords.forEach { record ->
                                    HistoryRow(
                                        title = buildString {
                                            append(record.studentName)
                                            record.studentRollNumber?.let {
                                                append(" - ")
                                                append(it)
                                            }
                                        },
                                        subtitle = buildString {
                                            append(record.studentRollNumber ?: "Roll not set")
                                            append(" - ")
                                            append(
                                                if (record.faceVerified && record.bluetoothVerified) {
                                                    "Face + Bluetooth verified"
                                                } else {
                                                    "Needs review"
                                                }
                                            )
                                            record.faceMatchScore?.let {
                                                append(" - Face ${(it * 100).toInt()}%")
                                            }
                                            record.bluetoothRssi?.let {
                                                append(" - ${it}dBm")
                                            }
                                            append(" - ")
                                            append(formatDateTime(record.markedAt))
                                            append(" - Code ${record.sessionCode}")
                                        }
                                    )
                                }
                            }
                            InfoCard(
                                "Roster Rule",
                                "Only records from the selected date and period are shown here."
                            )
                        }

                        TeacherAttendancePage.StudentReport -> {
                            HeaderCard("Student Attendance Report", "Select student and days to view present/absent")
                            if (reportStudentOptions.isEmpty()) {
                                InfoCard(
                                    "No Students Yet",
                                    "No attendance records found yet. Once students mark attendance, their report can be generated here."
                                )
                            } else {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(22.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.White)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Text("Student", style = MaterialTheme.typography.labelLarge, color = Color(0xFF647089))
                                        Row(
                                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            reportStudentOptions.forEach { option ->
                                                FilterChip(
                                                    selected = selectedReportStudentKey == option.key,
                                                    onClick = { selectedReportStudentKey = option.key },
                                                    label = { Text(option.label) }
                                                )
                                            }
                                        }
                                        Text("Days", style = MaterialTheme.typography.labelLarge, color = Color(0xFF647089))
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            listOf(7, 15, 30, 60).forEach { dayCount ->
                                                FilterChip(
                                                    selected = selectedReportDays == dayCount,
                                                    onClick = { selectedReportDays = dayCount },
                                                    label = { Text("$dayCount days") }
                                                )
                                            }
                                        }
                                    }
                                }

                                val presentCount = selectedStudentReportRows.count { it.isPresent }
                                val absentCount = selectedStudentReportRows.count { !it.isPresent }
                                MetricGrid(
                                    items = listOf(
                                        MetricItem("Total Sessions", selectedStudentReportRows.size.toString(), "Slots in selected range"),
                                        MetricItem("Present", presentCount.toString(), "Student marked attendance"),
                                        MetricItem("Absent", absentCount.toString(), "No attendance found"),
                                        MetricItem("Range", "Last $selectedReportDays days", "From today backwards")
                                    )
                                )

                                HistoryCard(
                                    title = "Detailed Attendance",
                                    isLoading = isLoadingHistory || isLoadingAttendance,
                                    isEmpty = selectedStudentReportRows.isEmpty(),
                                    emptyText = "No class sessions were found in the selected date range."
                                ) {
                                    selectedStudentReportRows.forEach { row ->
                                        HistoryRow(
                                            title = "${formatCalendarDate(row.classDate)} | ${classPeriodLabel(row.classPeriod)}",
                                            subtitle = buildString {
                                                append(if (row.isPresent) "Present" else "Absent")
                                                row.markedAt?.let {
                                                    append(" - Marked at ")
                                                    append(formatDateTime(it))
                                                }
                                                row.sessionCode?.let {
                                                    append(" - Code ")
                                                    append(it)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }

                    }
                }

                TeacherSection.Profile -> {
                    HeaderCard("Teacher Profile", "Account and session details")
                    SummaryCard(
                        title = "Teacher Account",
                        items = listOf(
                            "Name: ${profile.fullName}",
                            "Email: ${profile.email}",
                            "Portal: ${profile.role.label}",
                            "Total sessions loaded: ${sessions.size}"
                        )
                    )
                    InfoCard("Broadcast Status", bleStatus)
                    InfoCard("Current System Status", statusText)
                }
            }
        }
    }
}

@Composable
private fun StudentDashboard(
    session: AuthSession,
    profile: UserProfile,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val attendanceHistory = remember { mutableStateListOf<AttendanceRecord>() }
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedSection by rememberSaveable { mutableStateOf(StudentSection.MarkAttendance) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var selectedPeriod by remember { mutableStateOf<Int?>(null) }
    var selectedHistoryDate by remember { mutableStateOf<LocalDate?>(null) }
    var selectedHistoryPeriod by remember { mutableStateOf<Int?>(null) }
    var studentProfile by remember(profile.id) { mutableStateOf(profile) }
    var enteredCode by rememberSaveable { mutableStateOf("") }
    var statusText by remember { mutableStateOf("Ready, ${profile.fullName}") }
    var isLoadingHistory by remember { mutableStateOf(true) }
    var isValidatingSession by remember { mutableStateOf(false) }
    var sessionValidated by remember { mutableStateOf(false) }
    var validatedSession by remember { mutableStateOf<Session?>(null) }
    var submissionDetails by remember { mutableStateOf<String?>(null) }
    var bleVerified by remember { mutableStateOf(false) }
    var faceVerified by remember { mutableStateOf(false) }
    var studentBleDetails by remember { mutableStateOf(studentBleDiagnostics(context).details) }
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isCheckingBluetooth by remember { mutableStateOf(false) }
    var isDetectingFace by remember { mutableStateOf(false) }
    var faceMatchScore by remember { mutableStateOf<Float?>(null) }
    var bluetoothRssi by remember { mutableStateOf<Int?>(null) }
    var lastBleVerifiedAt by remember { mutableStateOf<Long?>(null) }
    var lastFaceVerifiedAt by remember { mutableStateOf<Long?>(null) }
    var flowStep by rememberSaveable { mutableStateOf(AttendanceFlowStep.ClassSelection) }
    var otpInlineError by remember { mutableStateOf<String?>(null) }
    var resultSuccess by remember { mutableStateOf<Boolean?>(null) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var verificationError by remember { mutableStateOf<String?>(null) }
    var bluetoothUiState by remember { mutableStateOf(VerificationUiState.Pending) }
    var faceUiState by remember { mutableStateOf(VerificationUiState.Pending) }
    var pendingFaceCapture by remember { mutableStateOf(false) }
    var isAutoSubmitting by remember { mutableStateOf(false) }
    var submitAttemptCounter by remember { mutableLongStateOf(0L) }
    var activeSubmitAttemptId by remember { mutableLongStateOf(0L) }

    fun refreshAttendanceHistory() {
        scope.launch {
            isLoadingHistory = true
            val result = withContext(Dispatchers.IO) { loadStudentAttendance(session.accessToken, studentProfile) }
            attendanceHistory.clear()
            attendanceHistory.addAll(result)
            isLoadingHistory = false
        }
    }

    fun refreshStudentProfile() {
        scope.launch {
            val refreshed = withContext(Dispatchers.IO) {
                fetchUserProfile(session.accessToken, studentProfile.id)
            }
            if (refreshed != null) {
                studentProfile = refreshed
            }
        }
    }

    LaunchedEffect(studentProfile.id) {
        refreshStudentProfile()
        refreshAttendanceHistory()
    }

    val latestRecord = attendanceHistory.maxByOrNull { record -> record.markedAt }
    val filteredAttendanceHistory = selectedHistoryDate?.let { historyDate ->
        attendanceHistory.filter { record ->
            parseStoredLocalDate(record.classDate) == historyDate || epochMillisToLocalDate(record.markedAt) == historyDate
        }
    } ?: attendanceHistory
    val periodFilteredAttendanceHistory = selectedHistoryPeriod?.let { period ->
        filteredAttendanceHistory.filter { it.classPeriod == period }
    } ?: filteredAttendanceHistory
    val faceEnrollmentReady = hasFaceEnrollment(studentProfile)
    fun hasBluetoothRuntimePermissions(): Boolean {
        val bluetoothGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
        return bluetoothGranted
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val cameraGranted = grants[Manifest.permission.CAMERA] == true || hasCameraPermission(context)
        val bluetoothGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            grants[Manifest.permission.BLUETOOTH_SCAN] == true &&
                grants[Manifest.permission.BLUETOOTH_CONNECT] == true
        } else {
            grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        }

        statusText = when {
            cameraGranted && bluetoothGranted -> "Access granted."
            cameraGranted -> "Camera granted. Bluetooth still unavailable."
            else -> "Bluetooth and camera access are required."
        }
        studentBleDetails = studentBleDiagnostics(context).details
    }

    val bluetoothEnableLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        studentBleDetails = studentBleDiagnostics(context).details
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap == null) {
            faceVerified = false
            lastFaceVerifiedAt = null
            statusText = "Face capture cancelled."
            faceUiState = VerificationUiState.Error
            verificationError = statusText
            pendingFaceCapture = false
            return@rememberLauncherForActivityResult
        }

        capturedBitmap = bitmap
        scope.launch {
            val enrolledBitmap = studentProfile.faceImageBase64?.let(::decodeBase64Bitmap)
            if (enrolledBitmap == null) {
                statusText = "Face enrollment is missing for this account. Register again and enroll face."
                faceUiState = VerificationUiState.Error
                verificationError = statusText
                pendingFaceCapture = false
                snackbarHostState.showSnackbar(statusText)
                return@launch
            }

            isDetectingFace = true
            statusText = "Running AI face verification..."
            val result = withTimeoutOrNull(12_000L) {
                withContext(Dispatchers.Default) {
                    verifyFaceWithModel(context, enrolledBitmap, bitmap)
                }
            } ?: FaceMatchResult(false, "Face verification timed out. Try again.")

            faceVerified = result.success
            faceMatchScore = result.similarity
            lastFaceVerifiedAt = if (result.success) nowMillis() else null
            statusText = result.message
            faceUiState = if (result.success) VerificationUiState.Success else VerificationUiState.Error
            verificationError = if (result.success) null else result.message
            pendingFaceCapture = false
            snackbarHostState.showSnackbar(result.message)
            isDetectingFace = false

        }
    }

    LaunchedEffect(selectedDate, selectedPeriod) {
        sessionValidated = false
        validatedSession = null
        bleVerified = false
        faceVerified = false
        bluetoothRssi = null
        faceMatchScore = null
        lastBleVerifiedAt = null
        lastFaceVerifiedAt = null
        submissionDetails = null
        otpInlineError = null
        resultSuccess = null
        resultMessage = null
        verificationError = null
        bluetoothUiState = VerificationUiState.Pending
        faceUiState = VerificationUiState.Pending
        pendingFaceCapture = false
        isAutoSubmitting = false
        activeSubmitAttemptId = 0L
        if (selectedSection == StudentSection.MarkAttendance) flowStep = AttendanceFlowStep.ClassSelection
    }

    LaunchedEffect(enteredCode) {
        sessionValidated = false
        validatedSession = null
        bleVerified = false
        faceVerified = false
        bluetoothRssi = null
        faceMatchScore = null
        lastBleVerifiedAt = null
        lastFaceVerifiedAt = null
        otpInlineError = null
        resultSuccess = null
        resultMessage = null
        verificationError = null
        bluetoothUiState = VerificationUiState.Pending
        faceUiState = VerificationUiState.Pending
        pendingFaceCapture = false
        isAutoSubmitting = false
        activeSubmitAttemptId = 0L
    }

    fun runBluetoothCheck() {
        if (isCheckingBluetooth || isAutoSubmitting) return
        scope.launch {
            verificationError = null
            if (!hasBluetoothRuntimePermissions()) {
                bluetoothUiState = VerificationUiState.Error
                statusText = "Bluetooth permission is required."
                verificationError = statusText
                permissionLauncher.launch(studentRequiredPermissions())
                return@launch
            }

            val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
            val adapter = bluetoothManager?.adapter
            if (adapter?.isEnabled == false) {
                bluetoothUiState = VerificationUiState.Error
                statusText = "Bluetooth is off. Turn it on to continue."
                verificationError = statusText
                bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                return@launch
            }

            bluetoothUiState = VerificationUiState.Loading
            isCheckingBluetooth = true
            statusText = "Scanning for teacher device..."
            studentBleDetails = studentBleDiagnostics(context).details
            val bleResult = withTimeoutOrNull(8_000L) {
                withContext(Dispatchers.IO) {
                    scanForTeacherBleDevice(context, enteredCode.trim())
                }
            } ?: BleVerificationResult(false, "Bluetooth scan timed out.")
            isCheckingBluetooth = false

            bleVerified = bleResult.success
            bluetoothRssi = bleResult.rssi
            lastBleVerifiedAt = if (bleResult.success) nowMillis() else null
            statusText = bleResult.message
            studentBleDetails = studentBleDiagnostics(context).details
            if (!bleResult.success) {
                bluetoothUiState = VerificationUiState.Error
                verificationError = bleResult.message
                return@launch
            }
            bluetoothUiState = VerificationUiState.Success
        }
    }

    fun runFaceVerification() {
        if (isDetectingFace || pendingFaceCapture || isAutoSubmitting) return
        verificationError = null
        if (!faceEnrollmentReady) {
            faceUiState = VerificationUiState.Error
            verificationError = "Face enrollment is missing. Please update your profile face data."
            statusText = verificationError ?: statusText
            return
        }
        if (!hasCameraPermission(context)) {
            faceUiState = VerificationUiState.Error
            verificationError = "Camera permission is needed for face verification."
            statusText = verificationError ?: statusText
            permissionLauncher.launch(studentRequiredPermissions())
            return
        }
        faceUiState = VerificationUiState.Loading
        pendingFaceCapture = true
        statusText = "Verifying face..."
        cameraLauncher.launch(null)
    }

    fun submitAttendanceManually() {
        if (isAutoSubmitting || !sessionValidated || validatedSession == null) return
        scope.launch {
            submitAttemptCounter += 1L
            val attemptId = submitAttemptCounter
            activeSubmitAttemptId = attemptId
            isAutoSubmitting = true
            statusText = "Submitting attendance..."
            val submitResult = withTimeoutOrNull(20_000L) {
                withContext(Dispatchers.IO) {
                    verifyAndRecord(
                        enteredCode = enteredCode.trim(),
                        studentProfile = studentProfile,
                        accessToken = session.accessToken,
                        expectedClassDate = selectedDate?.let(::localDateToStorageString),
                        expectedClassPeriod = selectedPeriod,
                        resolvedSession = validatedSession,
                        bluetoothVerified = bleVerified,
                        faceVerified = faceVerified,
                        faceMatchScore = faceMatchScore?.toDouble(),
                        bluetoothRssi = bluetoothRssi,
                        bluetoothVerifiedAtMillis = lastBleVerifiedAt,
                        faceVerifiedAtMillis = lastFaceVerifiedAt
                    )
                }
            } ?: AttendanceResult(false, "Attendance submission timed out. Check internet and retry.")
            if (activeSubmitAttemptId != attemptId) return@launch
            resultSuccess = submitResult.success
            resultMessage = submitResult.message
            statusText = submitResult.message
            if (submitResult.success) {
                refreshAttendanceHistory()
            }
            isAutoSubmitting = false
            activeSubmitAttemptId = 0L
            flowStep = AttendanceFlowStep.Result
        }
    }

    StudentScaffold(
        title = "Student Dashboard",
        subtitle = studentProfile.rollNumber?.let { "${studentProfile.fullName} - $it" } ?: studentProfile.fullName,
        selectedSection = selectedSection,
        onSelect = { selectedSection = it },
        onLogout = onLogout,
        snackbarHostState = snackbarHostState
    ) { pageModifier ->
        Column(modifier = pageModifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (selectedSection) {
                StudentSection.Home -> {
                    StudentHeaderCard("Home", "Your attendance overview")
                    MetricGrid(
                        items = listOf(
                            MetricItem(
                                "Latest Date",
                                latestRecord?.classDate?.let(::parseStoredLocalDate)?.let(::formatCalendarDate)
                                    ?: latestRecord?.markedAt?.let(::epochMillisToLocalDate)?.let(::formatCalendarDate)
                                    ?: "--",
                                "Most recent attendance date"
                            ),
                            MetricItem("Latest Period", classPeriodLabel(latestRecord?.classPeriod), "Most recent class period"),
                            MetricItem("Face Enrollment", if (faceEnrollmentReady) "Completed" else "Missing", "Required for verification"),
                            MetricItem("Saved Records", attendanceHistory.size.toString(), "Total attendance records")
                        )
                    )
                    SecondaryActionButton(
                        text = if (isLoadingHistory) "Refreshing..." else "Refresh Attendance",
                        onClick = { refreshAttendanceHistory() },
                        enabled = !isLoadingHistory
                    )
                    latestRecord?.let { record ->
                        InfoCard(
                            "Latest Marked Attendance",
                            buildString {
                                append(record.classDate?.let(::parseStoredLocalDate)?.let(::formatCalendarDate) ?: "--")
                                append(" | ")
                                append(classPeriodLabel(record.classPeriod))
                                append(" | ")
                                append(formatDateTime(record.markedAt))
                            }
                        )
                    }
                    InfoCard("System Status", statusText)
                }

                StudentSection.MarkAttendance -> {
                    StudentHeaderCard("Mark Attendance", "Guided attendance workflow")
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            when (flowStep) {
                                AttendanceFlowStep.ClassSelection -> {
                                    Text("Step 1 of 4 - Select Class", fontWeight = FontWeight.Bold, color = Color(0xFF163E82))
                                    DateFilterControls(
                                        title = "Class Date",
                                        selectedDate = selectedDate,
                                        onDateSelected = { selectedDate = it },
                                        onClearDate = { selectedDate = null },
                                        emptyText = "Select class date to continue.",
                                        clearLabel = "Clear Date"
                                    )
                                    PeriodFilterControls(
                                        title = "Class Period",
                                        selectedPeriod = selectedPeriod,
                                        onPeriodSelected = { selectedPeriod = it },
                                        allowAllOption = false,
                                        emptySelectionLabel = "Select class period to continue."
                                    )
                                    PrimaryActionButton(
                                        text = "Continue",
                                        onClick = { flowStep = AttendanceFlowStep.OtpValidation },
                                        enabled = selectedDate != null && selectedPeriod != null
                                    )
                                }

                                AttendanceFlowStep.OtpValidation -> {
                                    Text("Step 2 of 4 - OTP Verification", fontWeight = FontWeight.Bold, color = Color(0xFF163E82))
                                    OutlinedTextField(
                                        value = enteredCode,
                                        onValueChange = {
                                            enteredCode = it.uppercase().take(6)
                                            otpInlineError = null
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("Attendance Code (OTP)") },
                                        singleLine = true,
                                        isError = otpInlineError != null
                                    )
                                    otpInlineError?.let {
                                        Text(it, color = Color(0xFFB00020), style = MaterialTheme.typography.bodyMedium)
                                    }
                                    PrimaryActionButton(
                                        text = "Validate Session",
                                        loadingText = "Validating...",
                                        onClick = {
                                            scope.launch {
                                                otpInlineError = null
                                                if (enteredCode.length != 6) {
                                                    otpInlineError = "Enter valid 6-character OTP."
                                                    return@launch
                                                }
                                                isValidatingSession = true
                                                val lookup = withContext(Dispatchers.IO) {
                                                    lookupActiveSessionByCode(enteredCode.trim(), session.accessToken)
                                                }
                                                val activeSession = lookup.session
                                                if (activeSession == null) {
                                                    sessionValidated = false
                                                    validatedSession = null
                                                    otpInlineError = lookup.message
                                                    statusText = lookup.message
                                                    isValidatingSession = false
                                                    return@launch
                                                }
                                                val isDateMatch = activeSession.slotDate() == selectedDate
                                                val isPeriodMatch = activeSession.classPeriod == selectedPeriod
                                                if (!isDateMatch || !isPeriodMatch) {
                                                    sessionValidated = false
                                                    validatedSession = null
                                                    otpInlineError =
                                                        "Code is for ${formatCalendarDate(activeSession.slotDate())} ${classPeriodLabel(activeSession.classPeriod)}."
                                                    isValidatingSession = false
                                                    return@launch
                                                }
                                                sessionValidated = true
                                                validatedSession = activeSession
                                                isValidatingSession = false
                                                flowStep = AttendanceFlowStep.AutoVerification
                                            }
                                        },
                                        enabled = !isValidatingSession
                                    )
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                                        TextButton(
                                            onClick = { flowStep = AttendanceFlowStep.ClassSelection },
                                            enabled = !isValidatingSession
                                        ) {
                                            Text("Go back")
                                        }
                                    }
                                }

                                AttendanceFlowStep.AutoVerification -> {
                                    Text("Step 3 of 4 - Verification", fontWeight = FontWeight.Bold, color = Color(0xFF163E82))
                                    Text(
                                        when (bluetoothUiState) {
                                            VerificationUiState.Loading -> "⏳ Scanning for teacher device..."
                                            VerificationUiState.Success -> "✔ Teacher device verified"
                                            VerificationUiState.Error -> "✖ Bluetooth verification failed"
                                            VerificationUiState.Pending -> "⏳ Waiting to start Bluetooth scan..."
                                        }
                                    )
                                    Text(
                                        when (faceUiState) {
                                            VerificationUiState.Loading -> "⏳ Verifying face..."
                                            VerificationUiState.Success -> "✔ Face verified"
                                            VerificationUiState.Error -> "✖ Face verification failed"
                                            VerificationUiState.Pending -> "⏳ Waiting to start face verification..."
                                        }
                                    )
                                    if (isAutoSubmitting) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text("Submitting attendance...")
                                        }
                                    }
                                    verificationError?.let {
                                        Text(it, color = Color(0xFFB00020), style = MaterialTheme.typography.bodyMedium)
                                    }
                                    if (bluetoothUiState != VerificationUiState.Success) {
                                        PrimaryActionButton(
                                            text = "Check Bluetooth",
                                            loadingText = "Checking...",
                                            onClick = { runBluetoothCheck() },
                                            enabled = !isCheckingBluetooth && !isAutoSubmitting
                                        )
                                    }
                                    if (bluetoothUiState == VerificationUiState.Success && faceUiState != VerificationUiState.Success) {
                                        PrimaryActionButton(
                                            text = "Verify Face",
                                            loadingText = "Verifying...",
                                            onClick = { runFaceVerification() },
                                            enabled = !isDetectingFace && !pendingFaceCapture && !isAutoSubmitting
                                        )
                                    }
                                    if (bluetoothUiState == VerificationUiState.Success && faceUiState == VerificationUiState.Success) {
                                        PrimaryActionButton(
                                            text = "Mark Attendance",
                                            loadingText = "Submitting...",
                                            onClick = { submitAttendanceManually() },
                                            enabled = !isAutoSubmitting
                                        )
                                    }
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                                        TextButton(
                                            onClick = { flowStep = AttendanceFlowStep.OtpValidation },
                                            enabled = !isCheckingBluetooth && !isDetectingFace && !isAutoSubmitting
                                        ) {
                                            Text("Go back")
                                        }
                                    }
                                }

                                AttendanceFlowStep.Result -> {
                                    Text("Step 4 of 4 - Attendance Result", fontWeight = FontWeight.Bold, color = Color(0xFF163E82))
                                    Text(
                                        text = if (resultSuccess == true) "✔ Attendance Marked" else "✖ Attendance Failed",
                                        color = if (resultSuccess == true) Color(0xFF117A65) else Color(0xFFB00020),
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    resultMessage?.let {
                                        Text(it, style = MaterialTheme.typography.bodyMedium)
                                    }
                                    if (resultSuccess == true) {
                                        PrimaryActionButton(
                                            text = "Done",
                                            onClick = { flowStep = AttendanceFlowStep.ClassSelection }
                                        )
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                                            TextButton(onClick = { flowStep = AttendanceFlowStep.AutoVerification }) {
                                                Text("Go back")
                                            }
                                        }
                                    } else {
                                        PrimaryActionButton(
                                            text = "Retry",
                                            onClick = {
                                                resultSuccess = null
                                                resultMessage = null
                                                verificationError = null
                                                bluetoothUiState = VerificationUiState.Pending
                                                faceUiState = VerificationUiState.Pending
                                                flowStep = AttendanceFlowStep.AutoVerification
                                            }
                                        )
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                                            TextButton(onClick = { flowStep = AttendanceFlowStep.OtpValidation }) {
                                                Text("Go back")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                StudentSection.History -> {
                    StudentHeaderCard("History", "View your previous attendance records")
                    SecondaryActionButton(
                        text = if (isLoadingHistory) "Refreshing..." else "Refresh History",
                        onClick = { refreshAttendanceHistory() },
                        enabled = !isLoadingHistory
                    )
                    DateFilterControls(
                        title = "History Date",
                        selectedDate = selectedHistoryDate,
                        onDateSelected = { selectedHistoryDate = it },
                        onClearDate = { selectedHistoryDate = null },
                        emptyText = "Filter by date (optional)."
                    )
                    PeriodFilterControls(
                        title = "History Period",
                        selectedPeriod = selectedHistoryPeriod,
                        onPeriodSelected = { selectedHistoryPeriod = it }
                    )
                    HistoryCard(
                        title = "Attendance Records",
                        isLoading = isLoadingHistory,
                        isEmpty = periodFilteredAttendanceHistory.isEmpty(),
                        emptyText = "No attendance records found for selected filters."
                    ) {
                        periodFilteredAttendanceHistory.forEach { record ->
                            HistoryRow(
                                title = buildString {
                                    append(record.classDate?.let(::parseStoredLocalDate)?.let(::formatCalendarDate) ?: "--")
                                    append(" | ")
                                    append(classPeriodLabel(record.classPeriod))
                                },
                                subtitle = buildString {
                                    append("Code ${record.sessionCode}")
                                    append(" | ")
                                    append(formatDateTime(record.markedAt))
                                    record.teacherName?.let {
                                        append(" | ")
                                        append(it)
                                    }
                                }
                            )
                        }
                    }
                }

                StudentSection.Profile -> {
                    StudentHeaderCard("Profile", "Student account details")
                    SecondaryActionButton(
                        text = "Refresh Profile",
                        onClick = { refreshStudentProfile() }
                    )
                    SummaryCard(
                        title = "Account Details",
                        items = listOf(
                            "Name: ${studentProfile.fullName}",
                            "Email: ${studentProfile.email}",
                            "Roll Number: ${studentProfile.rollNumber ?: "--"}",
                            "Face Enrollment: ${if (faceEnrollmentReady) "Completed" else "Missing"}"
                        )
                    )
                    MetricGrid(
                        items = listOf(
                            MetricItem("Total Records", attendanceHistory.size.toString(), "Attendance saved so far"),
                            MetricItem("Role", studentProfile.role.label, "Account role"),
                            MetricItem(
                                "Face Updated",
                                studentProfile.faceEnrolledAt?.let(::formatDateTime) ?: "--",
                                "Latest enrollment timestamp"
                            ),
                            MetricItem("Status", "Active", "Profile is ready")
                        )
                    )
                    InfoCard("System Status", statusText)
                }
            }

            capturedBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Captured face preview",
                    modifier = Modifier.fillMaxWidth().height(220.dp)
                )
            }
        }
    }
}

@Composable
private fun HeaderCard(title: String, subtitle: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color(0xFF112D5A), Color(0xFF215DB3), Color(0xFF7DB9FF))
                    )
                )
                .padding(26.dp)
        ) {
            val expanded = this.maxWidth > 500.dp
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 24.dp, y = (-12).dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.22f), Color.Transparent)
                        ),
                        RoundedCornerShape(999.dp)
                    )
                    .padding(44.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SurfaceTag(
                    text = "SmartAttendance Workspace",
                    light = true
                )
                Text(
                    title,
                    style = if (expanded) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.9f)
                )
            }
        }
    }
}

@Composable
private fun StudentHeaderCard(title: String, subtitle: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFFE2E8F5), RoundedCornerShape(24.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF163E82)
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF5A6B85)
            )
        }
    }
}

@Composable
private fun SessionCard(title: String, primaryText: String, secondaryText: String, accentColor: Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        listOf(
                            accentColor.copy(alpha = 0.18f),
                            Color.White,
                            Color(0xFFF8FBFF)
                        )
                    )
                )
                .border(1.dp, accentColor.copy(alpha = 0.22f), RoundedCornerShape(28.dp))
                .padding(22.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SurfaceTag(title)
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    primaryText,
                    style = MaterialTheme.typography.displaySmall,
                    color = accentColor,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(secondaryText, style = MaterialTheme.typography.bodyLarge, color = Color(0xFF2C3445))
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFFFFFFFF), Color(0xFFF5F9FF))
                    )
                )
                .border(1.dp, Color(0xFFD7E4F7), RoundedCornerShape(24.dp))
                .padding(20.dp)
        ) {
            SurfaceTag(title)
            Spacer(modifier = Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyLarge, color = Color(0xFF2C3445))
        }
    }
}

@Composable
private fun DateFilterControls(
    title: String,
    selectedDate: LocalDate?,
    onDateSelected: (LocalDate) -> Unit,
    onClearDate: () -> Unit,
    emptyText: String = "Showing all days",
    clearLabel: String = "Clear Filter"
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SurfaceTag(title)
            Text(
                selectedDate?.let(::formatCalendarDate) ?: emptyText,
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFF2C3445)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        val initialDate = selectedDate ?: LocalDate.now()
                        DatePickerDialog(
                            context,
                            { _, year, month, dayOfMonth ->
                                onDateSelected(LocalDate.of(year, month + 1, dayOfMonth))
                            },
                            initialDate.year,
                            initialDate.monthValue - 1,
                            initialDate.dayOfMonth
                        ).show()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (selectedDate == null) "Select Date" else "Change Date")
                }
                OutlinedButton(
                    onClick = onClearDate,
                    enabled = selectedDate != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(clearLabel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodFilterControls(
    title: String,
    selectedPeriod: Int?,
    onPeriodSelected: (Int?) -> Unit,
    allowAllOption: Boolean = true,
    emptySelectionLabel: String = "Showing attendance for every period"
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SurfaceTag(title)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (allowAllOption) {
                    FilterChip(
                        selected = selectedPeriod == null,
                        onClick = { onPeriodSelected(null) },
                        label = { Text("All Periods") }
                    )
                }
                classPeriods.forEach { period ->
                    FilterChip(
                        selected = selectedPeriod == period,
                        onClick = { onPeriodSelected(period) },
                        label = { Text(classPeriodLabel(period)) }
                    )
                }
            }
            Text(
                selectedPeriod?.let(::classPeriodLabel) ?: emptySelectionLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF5C6D86)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionSetupCard(
    selectedDate: LocalDate,
    selectedPeriod: Int?,
    onSelectDate: (LocalDate) -> Unit,
    onSelectPeriod: (Int) -> Unit
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SurfaceTag("Class Setup")
            Text(
                "Choose the class date and period before creating attendance.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFF2C3445)
            )
            OutlinedButton(
                onClick = {
                    DatePickerDialog(
                        context,
                        { _, year, month, dayOfMonth ->
                            onSelectDate(LocalDate.of(year, month + 1, dayOfMonth))
                        },
                        selectedDate.year,
                        selectedDate.monthValue - 1,
                        selectedDate.dayOfMonth
                    ).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Class Date: ${formatCalendarDate(selectedDate)}")
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                classPeriods.forEach { period ->
                    FilterChip(
                        selected = selectedPeriod == period,
                        onClick = { onSelectPeriod(period) },
                        label = { Text(classPeriodLabel(period)) }
                    )
                }
            }
            Text(
                selectedPeriod?.let(::classPeriodLabel) ?: "Select the period number to continue.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF5C6D86)
            )
        }
    }
}

@Composable
private fun SlotSummaryCard(
    summary: AttendanceSlotSummary,
    showSessionCount: Boolean = true,
    countLabel: String = "${summary.studentCount} students marked",
    onOpen: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF0E2E5E), Color(0xFF184A8F), Color(0xFF3E8C9A))
                    )
                )
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SurfaceTag(
                    text = "${formatCalendarDate(summary.key.classDate)} | ${classPeriodLabel(summary.key.classPeriod)}",
                    light = true
                )
                Text(
                    countLabel,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    buildString {
                        if (showSessionCount) {
                            append("Sessions: ${summary.sessionCount} | ")
                        }
                        append("Fully verified: ${summary.verifiedCount} | Code: ${summary.latestCode ?: "--"}")
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.92f)
                )
                Text(
                    summary.latestMarkedAt?.let { "Last marked ${formatDateTime(it)}" } ?: "Open this slot to view the roster.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.82f)
                )
            }
        }
    }
}

@Composable
private fun RosterContextCard(
    selectedDate: LocalDate?,
    selectedPeriod: Int?,
    activeCode: String?,
    studentCount: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1729))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SurfaceTag("Selected Attendance Slot", light = true)
            Text(
                buildString {
                    append(selectedDate?.let(::formatCalendarDate) ?: "Pick a date")
                    append(" | ")
                    append(selectedPeriod?.let(::classPeriodLabel) ?: "Pick a period")
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                "Students marked: $studentCount${activeCode?.let { " | Live code: $it" } ?: ""}",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.86f)
            )
        }
    }
}

@Composable
private fun SummaryCard(title: String, items: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFFFFFBF0), Color(0xFFFFFFFF))
                    )
                )
                .border(1.dp, Color(0xFFFFE0A9), RoundedCornerShape(24.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SurfaceTag(title)
            items.forEachIndexed { index, item ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFFFFD58D), RoundedCornerShape(999.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("${index + 1}", color = Color(0xFF5B3A00), style = MaterialTheme.typography.labelMedium)
                    }
                    Text(item, style = MaterialTheme.typography.bodyLarge, color = Color(0xFF2C3445))
                }
            }
        }
    }
}

@Composable
private fun ActionHighlightCard(title: String, body: String, tone: Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.horizontalGradient(
                        listOf(tone.copy(alpha = 0.18f), tone.copy(alpha = 0.05f), Color.White)
                    )
                )
                .border(1.dp, tone.copy(alpha = 0.16f), RoundedCornerShape(24.dp))
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SurfaceTag(title)
                Text(body, style = MaterialTheme.typography.bodyLarge, color = Color(0xFF43546D))
            }
        }
    }
}

@Composable
private fun SurfaceTag(text: String, light: Boolean = false) {
    Row(
        modifier = Modifier
            .background(
                if (light) Color.White.copy(alpha = 0.16f) else Color(0xFFEAF2FF),
                RoundedCornerShape(999.dp)
        )
        .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .background(
                    if (light) Color.White.copy(alpha = 0.26f) else Color(0xFF295CA8),
                    RoundedCornerShape(999.dp)
                )
                .width(10.dp)
                .height(10.dp)
        )
        Text(
            text = text,
            color = if (light) Color.White else Color(0xFF295CA8),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private data class MetricItem(
    val label: String,
    val value: String,
    val caption: String
)

private val classPeriods = (1..8).toList()

private data class AttendanceSlotKey(
    val classDate: LocalDate,
    val classPeriod: Int?
)

private data class AttendanceSlotSummary(
    val key: AttendanceSlotKey,
    val sessionCount: Int,
    val studentCount: Int,
    val verifiedCount: Int,
    val latestCode: String?,
    val latestMarkedAt: Long?
)

private data class TeacherStudentOption(
    val key: String,
    val label: String
)

private data class StudentAttendanceReportRow(
    val classDate: LocalDate,
    val classPeriod: Int?,
    val isPresent: Boolean,
    val markedAt: Long?,
    val sessionCode: String?
)

@Composable
private fun MetricGrid(items: List<MetricItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowItems.forEach { item ->
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                    ) {
                        Column(
                            modifier = Modifier
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color(0xFFFFFFFF), Color(0xFFF2F7FF))
                                    )
                                )
                                .border(1.dp, Color(0xFFD7E4F6), RoundedCornerShape(22.dp))
                                .padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(item.label, style = MaterialTheme.typography.labelMedium, color = Color(0xFF6E7B92))
                            Text(item.value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color(0xFF20324D))
                            Text(item.caption, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF5C6D86))
                        }
                    }
                }
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(
    title: String,
    isLoading: Boolean,
    isEmpty: Boolean,
    emptyText: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFFFFFFFF), Color(0xFFF6FAFF))
                    )
                )
                .border(1.dp, Color(0xFFD7E4F6), RoundedCornerShape(28.dp))
                .padding(20.dp)
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = Color(0xFF647089))
            Spacer(modifier = Modifier.height(12.dp))
            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                if (isEmpty) {
                    Text(emptyText, style = MaterialTheme.typography.bodyLarge, color = Color(0xFF66768E))
                } else {
                    content()
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(title: String, subtitle: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFBFDFF))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF66768E))
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
}

@Composable
private fun ButtonLabel(loading: Boolean, idleText: String, loadingText: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.width(18.dp).height(18.dp),
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(10.dp))
        }
        Text(if (loading) loadingText else idleText)
    }
}

@Composable
private fun VerificationChipRow(
    bleVerified: Boolean,
    faceVerified: Boolean,
    faceMatchScore: Float?,
    bluetoothRssi: Int?
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AssistChip(
            onClick = {},
            label = {
                Text(
                    if (bleVerified) {
                        "Bluetooth OK${bluetoothRssi?.let { " (${it}dBm)" } ?: ""}"
                    } else {
                        "Bluetooth pending"
                    }
                )
            }
        )
        AssistChip(
            onClick = {},
            label = {
                Text(
                    if (faceVerified) {
                        "Face OK${faceMatchScore?.let { " (${(it * 100).toInt()}%)" } ?: ""}"
                    } else {
                        "Face pending"
                    }
                )
            }
        )
    }
}

private fun Session.slotDate(): LocalDate {
    return parseStoredLocalDate(classDate) ?: epochMillisToLocalDate(createdAt)
}

private fun AttendanceRecord.slotDate(): LocalDate {
    return parseStoredLocalDate(classDate) ?: epochMillisToLocalDate(markedAt)
}

private fun buildSlotSummaries(
    sessions: List<Session>,
    attendanceRecords: List<AttendanceRecord>
): List<AttendanceSlotSummary> {
    val sessionGroups = sessions.groupBy { AttendanceSlotKey(it.slotDate(), it.classPeriod) }
    val attendanceGroups = attendanceRecords.groupBy { AttendanceSlotKey(it.slotDate(), it.classPeriod) }
    val allKeys = (sessionGroups.keys + attendanceGroups.keys)
        .distinct()
        .sortedWith(compareByDescending<AttendanceSlotKey> { it.classDate }.thenByDescending { it.classPeriod ?: 0 })

    return allKeys.map { key ->
        val slotSessions = sessionGroups[key].orEmpty()
        val slotAttendance = attendanceGroups[key].orEmpty()
        AttendanceSlotSummary(
            key = key,
            sessionCount = slotSessions.size,
            studentCount = slotAttendance.size,
            verifiedCount = slotAttendance.count { it.faceVerified && it.bluetoothVerified },
            latestCode = slotSessions.maxByOrNull { it.createdAt }?.code ?: slotAttendance.maxByOrNull { it.markedAt }?.sessionCode,
            latestMarkedAt = slotAttendance.maxByOrNull { it.markedAt }?.markedAt
        )
    }
}

private fun buildStudentAttendanceReportRows(
    sessions: List<Session>,
    attendanceRecords: List<AttendanceRecord>,
    studentKey: String,
    daysBack: Int
): List<StudentAttendanceReportRow> {
    if (daysBack <= 0) return emptyList()
    val startDate = LocalDate.now().minusDays((daysBack - 1).toLong())
    val endDate = LocalDate.now()

    val slotSessions = sessions
        .filter { session ->
            val slotDate = session.slotDate()
            !slotDate.isBefore(startDate) && !slotDate.isAfter(endDate)
        }
        .groupBy { AttendanceSlotKey(it.slotDate(), it.classPeriod) }
        .mapNotNull { (_, groupedSessions) -> groupedSessions.maxByOrNull { it.createdAt } }
        .sortedWith(compareByDescending<Session> { it.slotDate() }.thenByDescending { it.classPeriod ?: 0 })

    if (slotSessions.isEmpty()) return emptyList()

    val studentRecords = attendanceRecords.filter { record ->
        val recordKey = record.studentId?.takeIf { it.isNotBlank() }
            ?: listOf(record.studentName.trim(), record.studentRollNumber.orEmpty().trim()).joinToString("|")
        recordKey == studentKey
    }
    val attendanceBySlot = studentRecords.groupBy { AttendanceSlotKey(it.slotDate(), it.classPeriod) }

    return slotSessions.map { session ->
        val key = AttendanceSlotKey(session.slotDate(), session.classPeriod)
        val matchedRecord = attendanceBySlot[key]?.maxByOrNull { it.markedAt }
        StudentAttendanceReportRow(
            classDate = key.classDate,
            classPeriod = key.classPeriod,
            isPresent = matchedRecord != null,
            markedAt = matchedRecord?.markedAt,
            sessionCode = session.code
        )
    }
}

private fun hasCameraPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
}

private fun isFaceEnrollmentComplete(profile: UserProfile): Boolean {
    return hasFaceEnrollment(profile)
}
