package com.project.smartattendance.pro

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.project.smartattendance.R
import com.project.smartattendance.databinding.ActivityAttendanceListBinding

class AttendanceListActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAttendanceListBinding
    private lateinit var repository: ProSupabaseRepository
    private val adapter = AttendanceAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAttendanceListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val accessToken = intent.getStringExtra(ProModuleExtras.EXTRA_ACCESS_TOKEN)
        if (accessToken.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.session_end_expired), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        repository = ProSupabaseRepository(accessToken)

        setSupportActionBar(binding.toolbarAttendanceList)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbarAttendanceList.setNavigationOnClickListener { finish() }

        binding.recyclerAttendance.layoutManager = LinearLayoutManager(this)
        binding.recyclerAttendance.adapter = adapter

        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
            ?: ProSessionStore.load(this)?.sessionId

        if (sessionId.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.session_open_list_prompt), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val sessionOtp = intent.getStringExtra(EXTRA_SESSION_OTP)
            ?: ProSessionStore.load(this)?.otp
            ?: "--"

        binding.textAttendanceSession.text = getString(R.string.attendance_session_meta, sessionOtp, sessionId)
        
        loadAttendance(sessionId)
    }

    private fun loadAttendance(sessionId: String) {
        setLoading(true)
        updateEmptyState(false)
        
        repository.fetchAttendanceList(sessionId) { result ->
            setLoading(false)
            result.onSuccess { records ->
                adapter.submitList(records)
                updateEmptyState(records.isEmpty())
            }.onFailure {
                updateEmptyState(adapter.itemCount == 0)
                Snackbar.make(binding.root, readableError(it), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.emptyStateView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.recyclerAttendance.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun setLoading(isLoading: Boolean) {
        binding.progressAttendance.visibility = if (isLoading) View.VISIBLE else View.GONE
    }

    companion object {
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_SESSION_OTP = "extra_session_otp"
    }
}
