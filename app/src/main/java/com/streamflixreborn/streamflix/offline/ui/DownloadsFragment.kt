package com.streamflixreborn.streamflix.offline.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.media3.common.util.UnstableApi
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.databinding.FragmentDownloadsBinding
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.offline.DownloadModule
import com.streamflixreborn.streamflix.offline.database.OfflineDatabase
import com.streamflixreborn.streamflix.offline.database.OfflineVideoEntity
import com.streamflixreborn.streamflix.utils.DialogTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@UnstableApi
class DownloadsFragment : Fragment() {

    private var _binding: FragmentDownloadsBinding? = null
    private val binding get() = _binding!!

    private lateinit var downloadsAdapter: DownloadsAdapter
    private var currentList = emptyList<OfflineVideoEntity>()
    private var progressUpdateJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDownloadsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        downloadsAdapter = DownloadsAdapter(
            onItemClick = { video -> handleItemClick(video) },
            onItemLongClick = { video -> handleItemLongClick(video) }
        )

        binding.rvDownloads.adapter = downloadsAdapter

        // Observe the Room database flow to keep the list updated
        viewLifecycleOwner.lifecycleScope.launch {
            OfflineDatabase.getInstance(requireContext())
                .offlineDao()
                .getAllFlow()
                .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
                .collect { list ->
                    currentList = list
                    downloadsAdapter.submitList(list)
                    
                    // Show or hide empty state layout
                    if (list.isEmpty()) {
                        binding.layoutEmptyDownloads.visibility = View.VISIBLE
                        binding.rvDownloads.visibility = View.GONE
                    } else {
                        binding.layoutEmptyDownloads.visibility = View.GONE
                        binding.rvDownloads.visibility = View.VISIBLE
                    }
                }
        }
    }

    override fun onResume() {
        super.onResume()
        startProgressUpdates()
    }

    override fun onPause() {
        super.onPause()
        progressUpdateJob?.cancel()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun startProgressUpdates() {
        progressUpdateJob?.cancel()
        progressUpdateJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val dm = DownloadModule.getDownloadManager(requireContext())
                val currentDownloads = dm.currentDownloads
                
                if (currentDownloads.isNotEmpty() && currentList.isNotEmpty()) {
                    val updatedList = currentList.map { entity ->
                        val matchingDownload = currentDownloads.find { it.request.id == entity.id }
                        if (matchingDownload != null) {
                            entity.copy(
                                progress = matchingDownload.percentDownloaded,
                                downloadedBytes = matchingDownload.bytesDownloaded,
                                totalBytes = matchingDownload.contentLength,
                                state = when (matchingDownload.state) {
                                    androidx.media3.exoplayer.offline.Download.STATE_QUEUED -> 0
                                    androidx.media3.exoplayer.offline.Download.STATE_DOWNLOADING -> 1
                                    androidx.media3.exoplayer.offline.Download.STATE_STOPPED -> 2
                                    androidx.media3.exoplayer.offline.Download.STATE_COMPLETED -> 3
                                    androidx.media3.exoplayer.offline.Download.STATE_FAILED -> 4
                                    else -> entity.state
                                }
                            )
                        } else {
                            entity
                        }
                    }
                    downloadsAdapter.submitList(updatedList)
                }
                delay(1000)
            }
        }
    }

    private fun handleItemClick(video: OfflineVideoEntity) {
        when (video.state) {
            3 -> { // Completed: Play offline
                val videoType = if (video.seasonNumber != null && video.episodeNumber != null) {
                    Video.Type.Episode(
                        id = video.id,
                        number = video.episodeNumber,
                        title = video.title,
                        poster = video.posterUrl,
                        overview = "",
                        tvShow = Video.Type.Episode.TvShow(
                            id = "",
                            title = video.title,
                            poster = video.posterUrl,
                            banner = "",
                            releaseDate = "",
                            imdbId = ""
                        ),
                        season = Video.Type.Episode.Season(
                            number = video.seasonNumber,
                            title = ""
                        )
                    )
                } else {
                    Video.Type.Movie(
                        id = video.id,
                        title = video.title,
                        releaseDate = "",
                        poster = video.posterUrl ?: "",
                        imdbId = ""
                    )
                }

                val bundle = Bundle().apply {
                    putString("id", video.id)
                    putString("title", video.title)
                    putString("subtitle", if (video.seasonNumber != null) "S${video.seasonNumber} E${video.episodeNumber}" else "")
                    putParcelable("videoType", videoType)
                    putBoolean("is_offline_playback", true)
                }
                findNavController().navigate(R.id.player, bundle)
            }
            1, 0 -> { // Downloading or Queued: Pause
                DownloadModule.pauseDownload(requireContext(), video.id)
            }
            2 -> { // Paused: Resume
                DownloadModule.resumeDownload(requireContext(), video.id)
            }
        }
    }

    private fun handleItemLongClick(video: OfflineVideoEntity) {
        val options = arrayOf("🗑️ Eliminar descarga")
        AlertDialog.Builder(requireContext())
            .setTitle(video.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> confirmDeleteDownload(video)
                }
            }
            .show()
    }


    private fun castDownloadedVideoToTv(video: OfflineVideoEntity) {
        if (video.state != 3) {
            android.widget.Toast.makeText(requireContext(), "La descarga aún no se ha completado", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val localServer = com.streamflixreborn.streamflix.cast.LocalMediaServer.getInstance(requireContext())
        val serverBaseUrl = localServer.startServer()
        if (serverBaseUrl == null) {
            android.widget.Toast.makeText(requireContext(), "Error iniciando servidor local en el teléfono", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val streamUrl = "$serverBaseUrl/offline_stream/${video.id}"
        val subtitle = if (video.seasonNumber != null) "S${video.seasonNumber} E${video.episodeNumber}" else "Descarga local"

        val payload = com.streamflixreborn.streamflix.cast.CastPayload(
            action = "PLAY",
            title = video.title,
            subtitle = subtitle,
            posterUrl = video.posterUrl,
            streamUrl = streamUrl,
            isOfflineDownload = true
        )

        com.streamflixreborn.streamflix.cast.ui.DeviceSelectorDialog.show(requireContext()) { device ->
            viewLifecycleOwner.lifecycleScope.launch {
                android.widget.Toast.makeText(requireContext(), "Enviando a ${device.name}...", android.widget.Toast.LENGTH_SHORT).show()
                com.streamflixreborn.streamflix.cast.MobileCastClient.sendPayloadToTv(
                    ipAddress = device.ipAddress,
                    port = device.port,
                    payload = payload,
                    onSuccess = {
                        android.widget.Toast.makeText(requireContext(), "📺 Transmitiendo en ${device.name}", android.widget.Toast.LENGTH_SHORT).show()
                        com.streamflixreborn.streamflix.cast.ui.CastRemoteControlDialog.show(
                            context = requireContext(),
                            device = device,
                            title = video.title,
                            subtitle = subtitle
                        )
                    },

                    onError = { err ->
                        android.widget.Toast.makeText(requireContext(), "Error al transmitir: $err", android.widget.Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }

    private fun confirmDeleteDownload(video: OfflineVideoEntity) {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.confirm_delete_title)
            .setMessage(R.string.confirm_delete_message)
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                DownloadModule.removeDownload(requireContext(), video.id)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .create()
        DialogTheme.style(dialog)
        dialog.show()
    }
}

