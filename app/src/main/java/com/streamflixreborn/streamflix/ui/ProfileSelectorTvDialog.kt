package com.streamflixreborn.streamflix.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.models.AvatarType
import com.streamflixreborn.streamflix.models.UserProfile
import com.streamflixreborn.streamflix.utils.UserProfileManager
import java.io.File

class ProfileSelectorTvDialog(
    context: Context,
    private val onProfileSelected: (UserProfile) -> Unit
) : Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    private var isSelecting = false
    private var isManageMode = false
    private lateinit var adapter: TvProfileAdapter
    private var profilesList = mutableListOf<UserProfile>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            statusBarColor = Color.BLACK
            navigationBarColor = Color.BLACK
        }

        val root = LayoutInflater.from(context).inflate(R.layout.dialog_profile_selector_tv, null)
        setContentView(root)

        val tvTitle = root.findViewById<TextView>(R.id.tvSelectorTitle)
        val tvSubtitle = root.findViewById<TextView>(R.id.tvSelectorSubtitle)
        val rvProfiles = root.findViewById<RecyclerView>(R.id.rvProfilesTv)
        val btnAddProfile = root.findViewById<Button>(R.id.btnTvAddProfile)
        val btnManageProfiles = root.findViewById<Button>(R.id.btnTvManageProfiles)

        profilesList = UserProfileManager.getProfiles(context).toMutableList()
        val activeProfile = UserProfileManager.getActiveProfile(context)
        val activeIndex = profilesList.indexOfFirst { it.id == activeProfile?.id }.coerceAtLeast(0)

        adapter = TvProfileAdapter(
            items = profilesList,
            onItemClick = { selectedProfile, itemView ->
                if (isSelecting) return@TvProfileAdapter
                if (isManageMode) {
                    val options = arrayOf("🗑️ Eliminar Perfil")
                    AlertDialog.Builder(context)
                        .setTitle("Administrar Perfil '${selectedProfile.name}'")
                        .setItems(options) { dialogInterface, which ->
                            when (which) {
                                0 -> {
                                    val profiles = UserProfileManager.getProfiles(context)
                                    if (profiles.size <= 1) {
                                        Toast.makeText(context, "No puedes eliminar el único perfil activo", Toast.LENGTH_SHORT).show()
                                    } else {
                                        AlertDialog.Builder(context)
                                            .setTitle("Eliminar Perfil")
                                            .setMessage("¿Deseas eliminar '${selectedProfile.name}'?")
                                            .setPositiveButton("Eliminar") { _, _ ->
                                                UserProfileManager.deleteProfile(context, selectedProfile.id)
                                                refreshProfilesList()
                                            }
                                            .setNegativeButton("Cancelar", null)
                                            .show()
                                    }
                                }
                            }
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                    return@TvProfileAdapter
                }

                isSelecting = true
                UserProfileManager.setActiveProfile(context, selectedProfile.id)

                // Zoom-in animation on active card
                val avatarCard = itemView.findViewById<androidx.cardview.widget.CardView>(R.id.cardProfileAvatar)
                val scaleX = ObjectAnimator.ofFloat(avatarCard, View.SCALE_X, 1f, 1.35f)
                val scaleY = ObjectAnimator.ofFloat(avatarCard, View.SCALE_Y, 1f, 1.35f)
                AnimatorSet().apply {
                    playTogether(scaleX, scaleY)
                    duration = 350
                    interpolator = DecelerateInterpolator()
                    start()
                }

                // Fade out subtitle and recycler view
                ObjectAnimator.ofFloat(tvSubtitle, View.ALPHA, 1f, 0f).setDuration(200).start()
                ObjectAnimator.ofFloat(rvProfiles, View.ALPHA, 1f, 0f).setDuration(200).start()
                ObjectAnimator.ofFloat(btnAddProfile, View.ALPHA, 1f, 0f).setDuration(200).start()
                ObjectAnimator.ofFloat(btnManageProfiles, View.ALPHA, 1f, 0f).setDuration(200).start()

                val titleFadeOut = ObjectAnimator.ofFloat(tvTitle, View.ALPHA, 1f, 0f).setDuration(200)
                titleFadeOut.addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        tvTitle.text = "¡Hola, ${selectedProfile.name}!"
                        tvTitle.textSize = 42f
                        ObjectAnimator.ofFloat(tvTitle, View.ALPHA, 0f, 1f).setDuration(300).start()
                    }
                })
                titleFadeOut.start()

                root.postDelayed({
                    onProfileSelected(selectedProfile)
                    dismiss()
                }, 1100)
            }
        )

        rvProfiles.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        rvProfiles.adapter = adapter

        rvProfiles.post {
            val viewHolder = rvProfiles.findViewHolderForAdapterPosition(activeIndex)
            viewHolder?.itemView?.requestFocus()
        }

        btnAddProfile.setOnClickListener {
            showCreateProfileInputDialog(context) { created ->
                refreshProfilesList()
            }
        }

        btnManageProfiles.setOnClickListener {
            isManageMode = !isManageMode
            btnManageProfiles.text = if (isManageMode) "✅ Listo" else "⚙️ Administrar Perfiles"
            tvSubtitle.text = if (isManageMode) "Selecciona un perfil para editarlo o eliminarlo" else "Selecciona tu perfil usando el control remoto"
            adapter.notifyDataSetChanged()
        }
    }

    private fun refreshProfilesList() {
        profilesList.clear()
        profilesList.addAll(UserProfileManager.getProfiles(context))
        adapter.notifyDataSetChanged()
    }

    private class TvProfileAdapter(
        private val items: List<UserProfile>,
        private val onItemClick: (UserProfile, View) -> Unit
    ) : RecyclerView.Adapter<TvProfileAdapter.ViewHolder>() {

        class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
            val cardAvatar: androidx.cardview.widget.CardView = view.findViewById(R.id.cardProfileAvatar)
            val imgAvatar: ImageView = view.findViewById(R.id.imgProfileAvatar)
            val tvName: TextView = view.findViewById(R.id.tvProfileName)
            val tvKidsBadge: TextView = view.findViewById(R.id.tvProfileKidsBadge)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_profile_tv, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val profile = items[position]
            holder.tvName.text = profile.name
            holder.tvKidsBadge.visibility = if (profile.isKids) View.VISIBLE else View.GONE

            if (profile.avatarType == AvatarType.PRESET) {
                val color = UserProfile.PRESET_AVATARS.find { it.first == profile.avatarValue }?.second ?: 0xFFE50914.toInt()
                holder.cardAvatar.setCardBackgroundColor(color)
                holder.imgAvatar.setImageDrawable(null)
            } else {
                holder.cardAvatar.setCardBackgroundColor(Color.TRANSPARENT)
                holder.imgAvatar.setImageURI(Uri.fromFile(File(profile.avatarValue)))
            }

            holder.itemView.isFocusable = true
            holder.itemView.isFocusableInTouchMode = true

            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    holder.cardAvatar.cardElevation = 16f
                    holder.itemView.animate().scaleX(1.14f).scaleY(1.14f).setDuration(180).start()
                } else {
                    holder.cardAvatar.cardElevation = 6f
                    holder.itemView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(180).start()
                }
            }

            holder.itemView.setOnClickListener {
                onItemClick(profile, holder.itemView)
            }
        }

        override fun getItemCount() = items.size
    }

    companion object {
        fun showCreateProfileInputDialog(context: Context, onCreated: (UserProfile) -> Unit) {
            val etInput = android.widget.EditText(context).apply {
                hint = "Ej. Maria, Pedro, Niños..."
                setSingleLine()
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                setPadding(40, 30, 40, 30)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
            }

            val dialog = android.app.AlertDialog.Builder(context)
                .setTitle("➕ Crear Nuevo Perfil")
                .setMessage("Ingresa el nombre del nuevo perfil:")
                .setView(etInput)
                .setPositiveButton("Crear") { _, _ ->
                    val name = etInput.text.toString().trim()
                    if (name.isNotEmpty()) {
                        val existingProfiles = UserProfileManager.getProfiles(context)
                        val avatarPreset = UserProfile.PRESET_AVATARS[existingProfiles.size % UserProfile.PRESET_AVATARS.size].first
                        val newProfile = UserProfile(
                            name = name,
                            avatarType = AvatarType.PRESET,
                            avatarValue = avatarPreset
                        )
                        UserProfileManager.saveProfile(context, newProfile)
                        onCreated(newProfile)
                    }
                }
                .setNegativeButton("Cancelar", null)
                .create()

            etInput.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                    actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT ||
                    actionId == android.view.inputmethod.EditorInfo.IME_NULL) {
                    val name = etInput.text.toString().trim()
                    if (name.isNotEmpty()) {
                        val existingProfiles = UserProfileManager.getProfiles(context)
                        val avatarPreset = UserProfile.PRESET_AVATARS[existingProfiles.size % UserProfile.PRESET_AVATARS.size].first
                        val newProfile = UserProfile(
                            name = name,
                            avatarType = AvatarType.PRESET,
                            avatarValue = avatarPreset
                        )
                        UserProfileManager.saveProfile(context, newProfile)
                        onCreated(newProfile)
                        dialog.dismiss()
                        true
                    } else false
                } else false
            }

            dialog.show()
        }
    }
}
