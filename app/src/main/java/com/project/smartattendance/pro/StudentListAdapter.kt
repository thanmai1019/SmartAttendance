package com.project.smartattendance.pro

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.project.smartattendance.databinding.ItemStudentListBinding
import com.project.smartattendance.formatDateTime
import com.project.smartattendance.parseSupabaseTimestamp

class StudentListAdapter : RecyclerView.Adapter<StudentListAdapter.StudentListViewHolder>() {
    private val items = mutableListOf<SupabaseAttendanceDto>()

    fun submitList(data: List<SupabaseAttendanceDto>) {
        items.clear()
        items.addAll(data)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StudentListViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return StudentListViewHolder(ItemStudentListBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: StudentListViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class StudentListViewHolder(
        private val binding: ItemStudentListBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SupabaseAttendanceDto) {
            val context = binding.root.context
            val markedAtText = parseSupabaseTimestamp(item.markedAt)?.let(::formatDateTime)
                ?: item.markedAt
            binding.textStudentName.text = item.studentName
            binding.textStudentId.text = context.getString(
                com.project.smartattendance.R.string.attendance_item_student_id,
                item.studentId
            )
            binding.textStudentTime.text = markedAtText
        }
    }
}
