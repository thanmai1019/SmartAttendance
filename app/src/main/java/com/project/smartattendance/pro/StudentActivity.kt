package com.project.smartattendance.pro

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.project.smartattendance.R
import com.project.smartattendance.databinding.ActivityStudentBinding
import com.project.smartattendance.formatDateTime
import com.project.smartattendance.nowMillis
import com.project.smartattendance.parseSupabaseTimestamp

class StudentActivity : AppCompatActivity() {
    private lateinit var binding: ActivityStudentBinding
    private lateinit var repository: ProSupabaseRepository
    private var accessToken: String? = null
    private var studentUserId: String? = null
    private var initialStudentName: String = ""
    private var initialStudentRoll: String = ""
    private var capturedFaceBitmap: Bitmap? = null
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            binding.textStudentDateTime.text = formatDateTime(nowMillis())
            clockHandler.postDelayed(this, 1000L)
        }
    }
    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
            if (bitmap != null) {
                capturedFaceBitmap = bitmap
                binding.imageFacePreview.setImageBitmap(bitmap)
                binding.textFaceStatus.text = getString(R.string.face_status_captured)
                binding.textFaceStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            } else {
                Snackbar.make(binding.root, getString(R.string.face_capture_cancelled), Snackbar.LENGTH_SHORT).show()
            }
        }
    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                cameraLauncher.launch(null)
            } else {
                Snackbar.make(binding.root, getString(R.string.camera_permission_required), Snackbar.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStudentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        accessToken = intent.getStringExtra(ProModuleExtras.EXTRA_ACCESS_TOKEN)
        studentUserId = intent.getStringExtra(ProModuleExtras.EXTRA_USER_ID)
        initialStudentName = intent.getStringExtra(ProModuleExtras.EXTRA_FULL_NAME).orEmpty()
        initialStudentRoll = intent.getStringExtra(ProModuleExtras.EXTRA_ROLL_NUMBER).orEmpty()
        if (accessToken.isNullOrBlank() || studentUserId.isNullOrBlank()) {
            finish()
            return
        }
        repository = ProSupabaseRepository(accessToken)

        setSupportActionBar(binding.toolbarStudent)
        binding.toolbarStudent.setNavigationOnClickListener { showMenu() }

        restoreDraft()

        binding.buttonCaptureFace.setOnClickListener { captureFace() }
        binding.buttonSubmitAttendance.setOnClickListener { submitAttendance() }
    }

    override fun onResume() {
        super.onResume()
        clockHandler.post(clockRunnable)
        flushPendingAttendance()
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockRunnable)
        saveDraft()
    }

    private fun showMenu() {
        PopupMenu(this, binding.toolbarStudent).apply {
            menuInflater.inflate(R.menu.menu_student_dashboard, menu)
            setOnMenuItemClickListener(::onMenuItemClick)
            show()
        }
    }

    private fun onMenuItemClick(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_student_home -> true
            R.id.action_open_teacher -> {
                startActivity(Intent(this, TeacherActivity::class.java).apply {
                    putExtra(ProModuleExtras.EXTRA_ACCESS_TOKEN, accessToken)
                    putExtra(ProModuleExtras.EXTRA_USER_ID, studentUserId)
                    putExtra(ProModuleExtras.EXTRA_FULL_NAME, initialStudentName)
                })
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                true
            }
            R.id.action_clear_form -> {
                clearForm()
                true
            }
            else -> false
        }
    }

    private fun captureFace() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                cameraLauncher.launch(null)
            }
            else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun submitAttendance() {
        val studentName = binding.inputStudentName.text?.toString()?.trim().orEmpty()
        val studentId = binding.inputStudentId.text?.toString()?.trim().orEmpty()
        val otp = binding.inputOtp.text?.toString()?.trim().orEmpty().uppercase()

        when {
            studentName.isBlank() -> showError(getString(R.string.error_enter_name))
            studentId.isBlank() -> showError(getString(R.string.error_enter_student_id))
            otp.length < 6 -> showError(getString(R.string.error_invalid_otp))
            capturedFaceBitmap == null -> showError(getString(R.string.error_capture_face))
            else -> verifySessionAndSubmit(studentName, studentId, otp)
        }
    }

    private fun verifySessionAndSubmit(studentName: String, studentId: String, otp: String) {
        setLoading(true)
        repository.findActiveSessionByOtp(otp) { sessionResult ->
            sessionResult.onSuccess { session ->
                if (session == null) {
                    setLoading(false)
                    showError(getString(R.string.error_invalid_live_otp))
                    return@onSuccess
                }

                val expiresAt = parseSupabaseTimestamp(session.expiresAt) ?: 0L
                if (expiresAt <= nowMillis()) {
                    repository.endSession(session.id) { }
                    setLoading(false)
                    showError(getString(R.string.error_session_expired))
                    return@onSuccess
                }

                repository.checkDuplicate(studentId, session.id) { duplicateResult ->
                    duplicateResult.onSuccess { exists ->
                        if (exists) {
                            setLoading(false)
                            showError(getString(R.string.error_duplicate_attendance))
                        } else {
                            repository.submitAttendance(
                                studentName = studentName,
                                studentId = studentId,
                                studentUuid = studentUserId,
                                session = session
                            ) { submitResult ->
                                setLoading(false)
                                submitResult.onSuccess {
                                    Snackbar.make(binding.root, getString(R.string.attendance_submit_success), Snackbar.LENGTH_LONG).show()
                                    binding.buttonSubmitAttendance.isEnabled = false
                                    saveDraft()
                                }.onFailure {
                                    val error = readableError(it)
                                    queueForRetry(studentName, studentId, otp)
                                    showError("$error ${getString(R.string.pending_retry_notice)}")
                                }
                            }
                        }
                    }.onFailure {
                        setLoading(false)
                        showError(readableError(it))
                    }
                }
            }.onFailure {
                setLoading(false)
                showError(readableError(it))
            }
        }
    }

    private fun clearForm() {
        binding.inputStudentName.text?.clear()
        binding.inputStudentId.text?.clear()
        binding.inputOtp.text?.clear()
        binding.imageFacePreview.setImageDrawable(null)
        binding.textFaceStatus.text = getString(R.string.face_status_pending)
        binding.textFaceStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        binding.buttonSubmitAttendance.isEnabled = true
        capturedFaceBitmap = null
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        Snackbar.make(binding.root, getString(R.string.draft_form_cleared), Snackbar.LENGTH_SHORT).show()
    }

    private fun restoreDraft() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        binding.inputStudentName.setText(prefs.getString(KEY_NAME, initialStudentName))
        binding.inputStudentId.setText(prefs.getString(KEY_STUDENT_ID, initialStudentRoll))
    }

    private fun saveDraft() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NAME, binding.inputStudentName.text?.toString()?.trim().orEmpty())
            .putString(KEY_STUDENT_ID, binding.inputStudentId.text?.toString()?.trim().orEmpty())
            .apply()
    }

    private fun queueForRetry(studentName: String, studentId: String, otp: String) {
        PendingAttendanceStore.enqueue(
            this,
            PendingAttendance(
                studentName = studentName,
                studentId = studentId,
                studentUuid = studentUserId,
                otp = otp,
                queuedAt = nowMillis()
            )
        )
    }

    private fun flushPendingAttendance() {
        val queue = PendingAttendanceStore.load(this)
        if (queue.isEmpty()) return
        val remaining = mutableListOf<PendingAttendance>()
        processPending(queue, 0, remaining)
    }

    private fun processPending(
        queue: List<PendingAttendance>,
        index: Int,
        remaining: MutableList<PendingAttendance>
    ) {
        if (index >= queue.size) {
            PendingAttendanceStore.save(this, remaining)
            return
        }
        val item = queue[index]
        repository.findActiveSessionByOtp(item.otp) { sessionResult ->
            sessionResult.onSuccess { session ->
                if (session == null) {
                    processPending(queue, index + 1, remaining)
                    return@onSuccess
                }
                repository.checkDuplicate(item.studentId, session.id) { duplicateResult ->
                    duplicateResult.onSuccess { exists ->
                        if (exists) {
                            processPending(queue, index + 1, remaining)
                        } else {
                            repository.submitAttendance(
                                studentName = item.studentName,
                                studentId = item.studentId,
                                studentUuid = item.studentUuid,
                                session = session
                            ) { submitResult ->
                                if (submitResult.isFailure) {
                                    remaining.add(item)
                                }
                                processPending(queue, index + 1, remaining)
                            }
                        }
                    }.onFailure {
                        remaining.add(item)
                        processPending(queue, index + 1, remaining)
                    }
                }
            }.onFailure {
                remaining.add(item)
                processPending(queue, index + 1, remaining)
            }
        }
    }

    private fun showError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun setLoading(isLoading: Boolean) {
        binding.progressStudent.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.buttonSubmitAttendance.isEnabled = !isLoading
        binding.buttonCaptureFace.isEnabled = !isLoading
        binding.buttonSubmitAttendance.text = if (isLoading) {
            getString(R.string.submitting_attendance)
        } else {
            getString(R.string.submit_attendance)
        }
    }

    companion object {
        private const val PREFS_NAME = "smartattendance_pro_student_form"
        private const val KEY_NAME = "student_name"
        private const val KEY_STUDENT_ID = "student_id"
    }
}
