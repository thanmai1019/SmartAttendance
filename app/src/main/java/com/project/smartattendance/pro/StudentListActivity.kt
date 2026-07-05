package com.project.smartattendance.pro

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.project.smartattendance.R
import com.project.smartattendance.databinding.ActivityStudentListBinding

class StudentListActivity : AppCompatActivity() {
    private lateinit var binding: ActivityStudentListBinding
    private lateinit var repository: ProSupabaseRepository
    private val adapter = StudentListAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStudentListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val accessToken = intent.getStringExtra(ProModuleExtras.EXTRA_ACCESS_TOKEN)
        if (accessToken.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.session_end_expired), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        repository = ProSupabaseRepository(accessToken)

        setSupportActionBar(binding.toolbarStudentList)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbarStudentList.setNavigationOnClickListener { finish() }

        binding.recyclerStudentList.layoutManager = LinearLayoutManager(this)
        binding.recyclerStudentList.adapter = adapter

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
        binding.textStudentListMeta.text = getString(R.string.attendance_session_meta_single, sessionOtp, sessionId)

        loadStudents(sessionId)
    }

    private fun loadStudents(sessionId: String) {
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

    private fun updateEmptyState(empty: Boolean) {
        binding.studentListEmptyState.visibility = if (empty) View.VISIBLE else View.GONE
        binding.recyclerStudentList.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun setLoading(loading: Boolean) {
        binding.progressStudentList.visibility = if (loading) View.VISIBLE else View.GONE
    }

    companion object {
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_SESSION_OTP = "extra_session_otp"
    }
}
