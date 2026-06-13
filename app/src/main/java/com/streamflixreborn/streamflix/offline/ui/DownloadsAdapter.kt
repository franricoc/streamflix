package com.streamflixreborn.streamflix.offline.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.databinding.ItemOfflineVideoBinding
import com.streamflixreborn.streamflix.offline.database.OfflineVideoEntity

class DownloadsAdapter(
    private val onItemClick: (OfflineVideoEntity) -> Unit,
    private val onItemLongClick: (OfflineVideoEntity) -> Unit
) : RecyclerView.Adapter<DownloadsAdapter.ViewHolder>() {

    private var items = emptyList<OfflineVideoEntity>()

    fun submitList(newList: List<OfflineVideoEntity>) {
        val diffCallback = object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newList.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return items[oldItemPosition].id == newList[newItemPosition].id
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return items[oldItemPosition] == newList[newItemPosition]
            }
        }
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        items = newList
        diffResult.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemOfflineVideoBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(private val binding: ItemOfflineVideoBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(video: OfflineVideoEntity) {
            binding.tvVideoTitle.text = video.title
            
            // Format S/E metadata
            binding.tvMetadata.text = if (video.seasonNumber != null && video.episodeNumber != null) {
                binding.root.context.getString(
                    R.string.tv_show_item_season_number_episode_number,
                    video.seasonNumber,
                    video.episodeNumber
                )
            } else {
                "Movie"
            }

            // Load Poster
            Glide.with(binding.root.context)
                .load(video.posterUrl)
                .placeholder(R.drawable.glide_fallback_cover)
                .error(R.drawable.glide_fallback_cover)
                .centerCrop()
                .into(binding.ivPoster)

            // Progress Bar and Percent Text
            val progressInt = video.progress.toInt().coerceIn(0, 100)
            binding.pbDownload.progress = progressInt
            binding.tvProgressPercent.text = "$progressInt%"

            // Status label & coloring
            val context = binding.root.context
            when (video.state) {
                0 -> { // Pending / Queued
                    binding.tvStatus.text = context.getString(R.string.download_queued)
                    binding.tvStatus.setTextColor(Color.parseColor("#B3FFFFFF"))
                }
                1 -> { // Downloading
                    binding.tvStatus.text = context.getString(R.string.download_started)
                    binding.tvStatus.setTextColor(Color.parseColor("#2196F3"))
                }
                2 -> { // Paused
                    binding.tvStatus.text = context.getString(R.string.download_paused)
                    binding.tvStatus.setTextColor(Color.parseColor("#FF9800"))
                }
                3 -> { // Completed
                    binding.tvStatus.text = context.getString(R.string.download_completed)
                    binding.tvStatus.setTextColor(Color.parseColor("#4CAF50"))
                    binding.pbDownload.progress = 100
                    binding.tvProgressPercent.text = "100%"
                }
                4 -> { // Failed
                    binding.tvStatus.text = context.getString(R.string.download_failed)
                    binding.tvStatus.setTextColor(Color.parseColor("#F44336"))
                }
            }

            // Click Handlers
            binding.root.setOnClickListener {
                onItemClick(video)
            }
            binding.root.setOnLongClickListener {
                onItemLongClick(video)
                true
            }
        }
    }
}
