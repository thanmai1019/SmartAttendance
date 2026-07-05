package com.project.smartattendance.pro

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.project.smartattendance.R
import com.project.smartattendance.databinding.ItemAttendanceBinding
import com.project.smartattendance.formatDateTime
import com.project.smartattendance.parseSupabaseTimestamp

class AttendanceAdapter : RecyclerView.Adapter<AttendanceAdapter.AttendanceViewHolder>() {
    private val items = mutableListOf<SupabaseAttendanceDto>()

    fun submitList(data: List<SupabaseAttendanceDto>) {
        items.clear()
        items.addAll(data)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AttendanceViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return AttendanceViewHolder(ItemAttendanceBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: AttendanceViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class AttendanceViewHolder(
        private val binding: ItemAttendanceBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SupabaseAttendanceDto) {
            val markedAtText = parseSupabaseTimestamp(item.markedAt)?.let(::formatDateTime)
                ?: item.markedAt

            binding.textItemStudentName.text = item.studentName
            val context = binding.root.context
            binding.textItemStudentId.text = context.getString(R.string.attendance_item_student_id, item.studentId)
            binding.textItemMarkedAt.text = context.getString(R.string.attendance_item_marked_at, markedAtText)
            binding.textItemStatus.text = context.getString(R.string.attendance_present)
        }
    }
}
