package com.project.smartattendance.pro

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.project.smartattendance.R
import com.project.smartattendance.SESSION_DURATION_MILLIS
import com.project.smartattendance.databinding.ActivityTeacherBinding
import com.project.smartattendance.formatDateTime
import com.project.smartattendance.generateCode
import com.project.smartattendance.nowMillis
import com.project.smartattendance.parseSupabaseTimestamp
import java.util.Locale

class TeacherActivity : AppCompatActivity() {
    private companion object {
        const val LIVE_COUNT_REFRESH_INTERVAL_MILLIS = 5_000L
    }

    private lateinit var binding: ActivityTeacherBinding
    private lateinit var repository: ProSupabaseRepository
    private var activeSession: TeacherSessionState? = null
    private var accessToken: String? = null
    private var teacherId: String? = null
    private var teacherName: String = "Teacher"
    private var countdownTimer: CountDownTimer? = null
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            binding.textTeacherDateTime.text = formatDateTime(nowMillis())
            clockHandler.postDelayed(this, 1000L)
        }
    }
    private val attendanceRefreshRunnable = object : Runnable {
        override fun run() {
            val session = activeSession
            if (session == null || session.endTimeMillis <= nowMillis()) {
                return
            }
            refreshAttendanceCount(showFeedback = false, showLoading = false)
            clockHandler.postDelayed(this, LIVE_COUNT_REFRESH_INTERVAL_MILLIS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTeacherBinding.inflate(layoutInflater)
        setContentView(binding.root)

        accessToken = intent.getStringExtra(ProModuleExtras.EXTRA_ACCESS_TOKEN)
        teacherId = intent.getStringExtra(ProModuleExtras.EXTRA_USER_ID)
        teacherName = intent.getStringExtra(ProModuleExtras.EXTRA_FULL_NAME).orEmpty().ifBlank { "Teacher" }
        if (accessToken.isNullOrBlank() || teacherId.isNullOrBlank()) {
            finish()
            return
        }
        repository = ProSupabaseRepository(accessToken)

        setSupportActionBar(binding.toolbarTeacher)
        binding.toolbarTeacher.setNavigationOnClickListener { showMenu() }

        binding.buttonStartAttendance.setOnClickListener { startAttendanceFlow() }
        binding.buttonEndSession.setOnClickListener { endCurrentSession(manual = true) }
        binding.buttonRefreshTeacher.setOnClickListener { refreshAttendanceCount(showFeedback = true) }
        binding.buttonViewAttendance.setOnClickListener { openAttendanceList() }

        restoreLocalSession()
    }

    override fun onResume() {
        super.onResume()
        clockHandler.post(clockRunnable)
        activeSession?.let {
            syncSessionUi(it)
            startLiveAttendanceRefresh()
        } ?: renderNoSession()
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockRunnable)
        clockHandler.removeCallbacks(attendanceRefreshRunnable)
        countdownTimer?.cancel()
    }

    private fun showMenu() {
        PopupMenu(this, binding.toolbarTeacher).apply {
            menuInflater.inflate(R.menu.menu_teacher_dashboard, menu)
            setOnMenuItemClickListener(::onMenuItemClick)
            show()
        }
    }

    private fun onMenuItemClick(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_teacher_home -> true
            R.id.action_view_attendance -> {
                openAttendanceList()
                true
            }
            R.id.action_open_student -> {
                startActivity(Intent(this, StudentActivity::class.java).apply {
                    putExtra(ProModuleExtras.EXTRA_ACCESS_TOKEN, accessToken)
                    putExtra(ProModuleExtras.EXTRA_USER_ID, teacherId)
                    putExtra(ProModuleExtras.EXTRA_FULL_NAME, teacherName)
                })
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                true
            }
            else -> false
        }
    }

    private fun restoreLocalSession() {
        val stored = ProSessionStore.load(this)
        if (stored == null) {
            renderNoSession()
            return
        }
        if (stored.endTimeMillis <= nowMillis()) {
            ProSessionStore.clear(this)
            repository.endSession(stored.sessionId) { }
            renderNoSession(expired = true)
            return
        }
        activeSession = stored
        syncSessionUi(stored)
        refreshAttendanceCount(showFeedback = false)
    }

    private fun startAttendanceFlow() {
        val localSession = activeSession
        if (localSession != null && localSession.endTimeMillis > nowMillis()) {
            Snackbar.make(binding.root, getString(R.string.session_active_exists), Snackbar.LENGTH_LONG).show()
            return
        }

        setLoading(true)
        repository.findLatestActiveSession(teacherId = teacherId.orEmpty()) { result ->
            result.onSuccess { remoteSession ->
                val remoteExpiry = parseSupabaseTimestamp(remoteSession?.expiresAt)
                when {
                    remoteSession == null -> createFreshSession()
                    remoteExpiry != null && remoteExpiry > nowMillis() -> {
                        setLoading(false)
                        val restored = TeacherSessionState(
                            sessionId = remoteSession.id,
                            otp = remoteSession.code,
                            startTimeMillis = parseSupabaseTimestamp(remoteSession.createdAt) ?: nowMillis(),
                            endTimeMillis = remoteExpiry
                        )
                        activeSession = restored
                        ProSessionStore.save(this, restored)
                        syncSessionUi(restored)
                        refreshAttendanceCount(showFeedback = false)
                        Snackbar.make(binding.root, getString(R.string.session_restored_message), Snackbar.LENGTH_LONG).show()
                    }
                    else -> {
                        repository.endSession(remoteSession.id) {
                            createFreshSession()
                        }
                    }
                }
            }.onFailure {
                setLoading(false)
                Snackbar.make(binding.root, readableError(it), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun createFreshSession() {
        val start = nowMillis()
        val end = start + SESSION_DURATION_MILLIS
        val otp = generateCode()

        repository.createSession(
            teacherName = teacherName,
            teacherId = teacherId,
            otp = otp,
            startTimeMillis = start,
            endTimeMillis = end
        ) { result ->
            setLoading(false)
            result.onSuccess { session ->
                val state = TeacherSessionState(
                    sessionId = session.id,
                    otp = session.code,
                    startTimeMillis = parseSupabaseTimestamp(session.createdAt) ?: start,
                    endTimeMillis = parseSupabaseTimestamp(session.expiresAt) ?: end
                )
                activeSession = state
                ProSessionStore.save(this, state)
                syncSessionUi(state)
                refreshAttendanceCount(showFeedback = false)
                Snackbar.make(binding.root, getString(R.string.session_started_message), Snackbar.LENGTH_SHORT).show()
            }.onFailure {
                Snackbar.make(binding.root, readableError(it), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun syncSessionUi(session: TeacherSessionState) {
        binding.textSessionStatus.text = getString(R.string.session_status_active)
        binding.textSessionStatus.setTextColor(ContextCompat.getColor(this, R.color.success_green))
        binding.textSessionOtp.text = session.otp
        binding.textSessionId.text = getString(R.string.session_id_text, session.sessionId)
        binding.buttonStartAttendance.text = getString(R.string.session_running)
        binding.buttonStartAttendance.isEnabled = false
        binding.buttonEndSession.isEnabled = true
        binding.buttonViewAttendance.isEnabled = true
        updateTimeLeft(session.endTimeMillis - nowMillis())
        startCountdown(session.endTimeMillis)
        startLiveAttendanceRefresh()
    }

    private fun startCountdown(endTimeMillis: Long) {
        countdownTimer?.cancel()
        val remaining = endTimeMillis - nowMillis()
        if (remaining <= 0L) {
            expireSession()
            return
        }

        countdownTimer = object : CountDownTimer(remaining, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                updateTimeLeft(millisUntilFinished)
            }

            override fun onFinish() {
                expireSession()
            }
        }.start()
    }

    private fun updateTimeLeft(remainingMillis: Long) {
        val totalSeconds = (remainingMillis.coerceAtLeast(0L) / 1000L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        val timerText = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        binding.textSessionTimer.text = timerText
        binding.textTimeLeft.text = getString(R.string.time_left_text, timerText)
    }

    private fun expireSession() {
        countdownTimer?.cancel()
        clockHandler.removeCallbacks(attendanceRefreshRunnable)
        val endedSession = activeSession
        activeSession = null
        ProSessionStore.clear(this)
        renderNoSession(expired = true)
        endedSession?.let { repository.endSession(it.sessionId) { } }
    }

    private fun endCurrentSession(manual: Boolean) {
        val session = activeSession
        if (session == null) {
            Snackbar.make(binding.root, getString(R.string.session_none_end_message), Snackbar.LENGTH_SHORT).show()
            return
        }

        setLoading(true)
        repository.endSession(session.sessionId) { result ->
            setLoading(false)
            result.onSuccess {
                countdownTimer?.cancel()
                clockHandler.removeCallbacks(attendanceRefreshRunnable)
                activeSession = null
                ProSessionStore.clear(this)
                renderNoSession(expired = !manual)
                val message = if (manual) getString(R.string.session_end_manual) else getString(R.string.session_end_expired)
                Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
            }.onFailure {
                Snackbar.make(binding.root, readableError(it), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun renderNoSession(expired: Boolean = false) {
        countdownTimer?.cancel()
        clockHandler.removeCallbacks(attendanceRefreshRunnable)
        binding.textSessionStatus.text = if (expired) getString(R.string.session_status_expired) else getString(R.string.session_status_idle)
        binding.textSessionStatus.setTextColor(
            ContextCompat.getColor(this, if (expired) R.color.error_red else R.color.text_secondary)
        )
        binding.textSessionOtp.text = if (expired) getString(R.string.session_status_expired) else getString(R.string.session_code_placeholder)
        binding.textSessionTimer.text = getString(R.string.session_time_default)
        binding.textSessionId.text = getString(R.string.session_id_placeholder)
        binding.textTotalCount.text = getString(R.string.total_count_default)
        binding.textTimeLeft.text = getString(R.string.time_left_default)
        binding.buttonStartAttendance.text = if (expired) getString(R.string.restart_attendance) else getString(R.string.start_attendance)
        binding.buttonStartAttendance.isEnabled = true
        binding.buttonEndSession.isEnabled = false
        binding.buttonViewAttendance.isEnabled = false
    }

    private fun startLiveAttendanceRefresh() {
        clockHandler.removeCallbacks(attendanceRefreshRunnable)
        clockHandler.post(attendanceRefreshRunnable)
    }

    private fun refreshAttendanceCount(showFeedback: Boolean, showLoading: Boolean = true) {
        val session = activeSession
        if (session == null) {
            binding.textTotalCount.text = getString(R.string.total_count_default)
            if (showFeedback) {
                Snackbar.make(binding.root, getString(R.string.session_refresh_none), Snackbar.LENGTH_SHORT).show()
            }
            return
        }

        if (showLoading) {
            setLoading(true)
        }
        repository.fetchAttendanceCount(session.sessionId) { result ->
            if (showLoading) {
                setLoading(false)
            }
            result.onSuccess { count ->
                binding.textTotalCount.text = getString(R.string.total_count_text, count)
                if (showFeedback) {
                    Snackbar.make(binding.root, getString(R.string.session_refresh_success), Snackbar.LENGTH_SHORT).show()
                }
            }.onFailure {
                Snackbar.make(binding.root, readableError(it), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun openAttendanceList() {
        val session = activeSession
        if (session == null) {
            Snackbar.make(binding.root, getString(R.string.session_open_list_prompt), Snackbar.LENGTH_SHORT).show()
            return
        }

        startActivity(Intent(this, StudentListActivity::class.java).apply {
            putExtra(StudentListActivity.EXTRA_SESSION_ID, session.sessionId)
            putExtra(StudentListActivity.EXTRA_SESSION_OTP, session.otp)
            putExtra(ProModuleExtras.EXTRA_ACCESS_TOKEN, accessToken)
        })
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun setLoading(isLoading: Boolean) {
        binding.progressTeacher.visibility = if (isLoading) View.VISIBLE else View.GONE
    }
}
