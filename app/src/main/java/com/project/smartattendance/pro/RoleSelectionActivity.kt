package com.project.smartattendance.pro

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.project.smartattendance.databinding.ActivityRoleSelectionBinding

class RoleSelectionActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRoleSelectionBinding
    private var accessToken: String? = null
    private var userId: String? = null
    private var fullName: String? = null
    private var rollNumber: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRoleSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        accessToken = intent.getStringExtra(ProModuleExtras.EXTRA_ACCESS_TOKEN)
        userId = intent.getStringExtra(ProModuleExtras.EXTRA_USER_ID)
        fullName = intent.getStringExtra(ProModuleExtras.EXTRA_FULL_NAME)
        rollNumber = intent.getStringExtra(ProModuleExtras.EXTRA_ROLL_NUMBER)
        if (accessToken.isNullOrBlank() || userId.isNullOrBlank()) {
            finish()
            return
        }

        binding.buttonOpenTeacher.setOnClickListener {
            startActivity(Intent(this, TeacherActivity::class.java).apply {
                putExtra(ProModuleExtras.EXTRA_ACCESS_TOKEN, accessToken)
                putExtra(ProModuleExtras.EXTRA_USER_ID, userId)
                putExtra(ProModuleExtras.EXTRA_FULL_NAME, fullName)
            })
        }

        binding.buttonOpenStudent.setOnClickListener {
            startActivity(Intent(this, StudentActivity::class.java).apply {
                putExtra(ProModuleExtras.EXTRA_ACCESS_TOKEN, accessToken)
                putExtra(ProModuleExtras.EXTRA_USER_ID, userId)
                putExtra(ProModuleExtras.EXTRA_FULL_NAME, fullName)
                putExtra(ProModuleExtras.EXTRA_ROLL_NUMBER, rollNumber)
            })
        }
    }
}
