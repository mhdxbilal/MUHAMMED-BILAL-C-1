package com.continental.player.download

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.continental.player.R
import com.continental.player.databinding.ItemDownloadBinding
import com.continental.player.util.FormatUtils

interface DownloadAdapterListener {
    fun onCancel(taskId: String)
    fun onRemove(taskId: String)
    fun onPlayCompleted(task: DownloadTask)
}

class DownloadAdapter(
    private val listener: DownloadAdapterListener
) : RecyclerView.Adapter<DownloadAdapter.ViewHolder>() {

    private var tasks: List<DownloadTask> = emptyList()

    fun submitList(newTasks: List<DownloadTask>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = tasks.size
            override fun getNewListSize() = newTasks.size
            override fun areItemsTheSame(o: Int, n: Int) = tasks[o].id == newTasks[n].id
            override fun areContentsTheSame(o: Int, n: Int): Boolean {
                val a = tasks[o]; val b = newTasks[n]
                return a.status == b.status &&
                    a.progressPercent == b.progressPercent &&
                    a.title == b.title &&
                    a.errorMessage == b.errorMessage
            }
        })
        tasks = newTasks
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemCount() = tasks.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(tasks[position])
    }

    inner class ViewHolder(private val b: ItemDownloadBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(task: DownloadTask) {
            b.tvTitle.text = task.displayTitle

            // Progress bar
            val showProgress = task.status == DownloadStatus.RUNNING || task.status == DownloadStatus.FETCHING_INFO
            b.progressDownload.isVisible = true
            b.progressDownload.isIndeterminate = task.status == DownloadStatus.FETCHING_INFO
            if (!b.progressDownload.isIndeterminate) {
                b.progressDownload.progress = task.progressPercent.toInt().coerceIn(0, 100)
            }

            // Status label
            val ctx = b.root.context
            b.tvStatus.text = when (task.status) {
                DownloadStatus.QUEUED -> ctx.getString(R.string.status_queued) + " · ${task.quality.label}"
                DownloadStatus.FETCHING_INFO -> ctx.getString(R.string.status_fetching)
                DownloadStatus.RUNNING -> buildRunningLabel(task)
                DownloadStatus.COMPLETED -> ctx.getString(R.string.status_completed)
                DownloadStatus.FAILED -> "${ctx.getString(R.string.status_failed)}: ${task.errorMessage ?: "Unknown error"}"
                DownloadStatus.CANCELLED -> ctx.getString(R.string.status_cancelled)
            }

            // Status icon tint
            val (iconRes, tintRes) = when (task.status) {
                DownloadStatus.COMPLETED -> Pair(R.drawable.ic_check, R.color.continental_success)
                DownloadStatus.FAILED, DownloadStatus.CANCELLED -> Pair(R.drawable.ic_close, R.color.continental_error)
                else -> Pair(R.drawable.ic_download, R.color.continental_gold)
            }
            b.ivStatusIcon.setImageResource(iconRes)
            b.ivStatusIcon.setColorFilter(ctx.getColor(tintRes))

            // Action buttons
            b.btnCancel.isVisible = task.isActive
            b.btnPlay.isVisible = task.status == DownloadStatus.COMPLETED && task.finalUri != null
            b.btnRemove.isVisible = !task.isActive

            b.btnCancel.setOnClickListener { listener.onCancel(task.id) }
            b.btnPlay.setOnClickListener { listener.onPlayCompleted(task) }
            b.btnRemove.setOnClickListener { listener.onRemove(task.id) }
        }

        private fun buildRunningLabel(task: DownloadTask): String {
            val pct = "${task.progressPercent.toInt()}%"
            val eta = if (task.etaSeconds > 0) " · ETA ${FormatUtils.formatEta(task.etaSeconds)}" else ""
            return "${task.quality.label} · $pct$eta"
        }
    }
}
