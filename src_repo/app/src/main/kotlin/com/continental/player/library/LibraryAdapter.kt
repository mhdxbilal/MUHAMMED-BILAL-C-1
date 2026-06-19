package com.continental.player.library

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.continental.player.data.LibraryRow
import com.continental.player.data.ResumeStore
import com.continental.player.data.VideoItem
import com.continental.player.databinding.ItemFolderHeaderBinding
import com.continental.player.databinding.ItemVideoBinding
import com.continental.player.util.FormatUtils
import com.continental.player.util.ThumbnailLoader
import kotlinx.coroutines.launch

interface LibraryAdapterListener {
    fun onVideoClick(video: VideoItem)
    fun onFolderToggle(folderPath: String)
    fun onFolderLongPress(folderPath: String, folderName: String, anchor: View)
}

class LibraryAdapter(
    private val lifecycleScope: LifecycleCoroutineScope,
    private val resumeStore: ResumeStore,
    private val listener: LibraryAdapterListener
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<LibraryRow> = emptyList()

    companion object {
        private const val TYPE_FOLDER = 0
        private const val TYPE_VIDEO = 1
        private const val THUMB_WIDTH_PX = 480
        private const val THUMB_HEIGHT_PX = 270
    }

    fun submitRows(newRows: List<LibraryRow>) {
        val diff = DiffUtil.calculateDiff(RowDiffCallback(rows, newRows))
        rows = newRows
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is LibraryRow.FolderHeader -> TYPE_FOLDER
        is LibraryRow.Video -> TYPE_VIDEO
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_FOLDER) {
            FolderViewHolder(ItemFolderHeaderBinding.inflate(inflater, parent, false))
        } else {
            VideoViewHolder(ItemVideoBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is LibraryRow.FolderHeader -> (holder as FolderViewHolder).bind(row)
            is LibraryRow.Video -> (holder as VideoViewHolder).bind(row.video)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is VideoViewHolder) holder.binding.ivThumbnail.setImageDrawable(null)
    }

    inner class FolderViewHolder(private val binding: ItemFolderHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(folder: LibraryRow.FolderHeader) {
            binding.tvFolderName.text = folder.folderName
            binding.tvFolderMeta.text = "${folder.videoCount} videos · " +
                FormatUtils.formatDurationLong(folder.totalDurationMs)
            binding.ivChevron.rotation = if (folder.isCollapsed) 180f else 0f

            binding.root.setOnClickListener { listener.onFolderToggle(folder.folderPath) }
            binding.root.setOnLongClickListener {
                listener.onFolderLongPress(folder.folderPath, folder.folderName, binding.root)
                true
            }
        }
    }

    inner class VideoViewHolder(val binding: ItemVideoBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(video: VideoItem) {
            binding.tvName.text = video.displayName
            binding.tvDuration.text = FormatUtils.formatDuration(video.durationMs)
            binding.tvMeta.text = "${FormatUtils.formatFileSize(video.sizeBytes)} · " +
                FormatUtils.formatDateAdded(video.dateAddedSec)

            val resumeEntry = resumeStore.getEntry(video.uri.toString())
            if (resumeEntry != null && video.durationMs > 0) {
                binding.progressResume.isVisible = true
                binding.progressResume.progress =
                    ((resumeEntry.positionMs * 100) / video.durationMs).toInt().coerceIn(0, 100)
            } else {
                binding.progressResume.isVisible = false
            }

            binding.ivThumbnail.setImageDrawable(null)
            val targetUri = video.uri
            lifecycleScope.launch {
                val bitmap = ThumbnailLoader.load(
                    binding.root.context, targetUri, THUMB_WIDTH_PX, THUMB_HEIGHT_PX
                )
                // Guard against the ViewHolder having been rebound to a different item
                // while the thumbnail was decoding off-thread.
                if (bindingAdapterPosition != RecyclerView.NO_POSITION &&
                    (rows.getOrNull(bindingAdapterPosition) as? LibraryRow.Video)?.video?.uri == targetUri
                ) {
                    binding.ivThumbnail.setImageBitmap(bitmap)
                }
            }

            binding.root.setOnClickListener { listener.onVideoClick(video) }
        }
    }

    private class RowDiffCallback(
        private val oldRows: List<LibraryRow>,
        private val newRows: List<LibraryRow>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = oldRows.size
        override fun getNewListSize() = newRows.size

        private fun keyOf(row: LibraryRow): String = when (row) {
            is LibraryRow.FolderHeader -> "F:${row.folderPath}"
            is LibraryRow.Video -> "V:${row.video.id}"
        }

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            keyOf(oldRows[oldItemPosition]) == keyOf(newRows[newItemPosition])

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            oldRows[oldItemPosition] == newRows[newItemPosition]
    }
}
