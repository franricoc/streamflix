package com.streamflixreborn.streamflix.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.card.MaterialCardView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.models.AvatarType
import com.streamflixreborn.streamflix.models.UserProfile
import com.streamflixreborn.streamflix.utils.UserProfileManager
import java.io.File

class ProfileSelectorDialog(
    context: Context,
    private val onProfileSelected: (UserProfile) -> Unit
) : Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    private var isSelecting = false

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

        val root = LayoutInflater.from(context).inflate(R.layout.dialog_profile_selector, null)
        setContentView(root)

        val tvTitle = root.findViewById<TextView>(R.id.tvSelectorTitle)
        val tvSubtitle = root.findViewById<TextView>(R.id.tvSelectorSubtitle)
        val vpCarousel = root.findViewById<ViewPager2>(R.id.vpProfileCarousel)
        val layoutDots = root.findViewById<LinearLayout>(R.id.layoutDots)

        val profiles = UserProfileManager.getProfiles(context)
        val activeProfile = UserProfileManager.getActiveProfile(context)

        val activeIndex = profiles.indexOfFirst { it.id == activeProfile?.id }.coerceAtLeast(0)

        // Setup ViewPager2 Carousel
        vpCarousel.adapter = CarouselAdapter(profiles) { selectedProfile, itemView ->
            if (isSelecting) return@CarouselAdapter
            isSelecting = true

            UserProfileManager.setActiveProfile(context, selectedProfile.id)

            // Zoom-in animation on tapped card
            val avatarCard = itemView.findViewById<MaterialCardView>(R.id.cardProfileAvatar)
            val scaleX = ObjectAnimator.ofFloat(avatarCard, View.SCALE_X, 1f, 1.35f)
            val scaleY = ObjectAnimator.ofFloat(avatarCard, View.SCALE_Y, 1f, 1.35f)
            AnimatorSet().apply {
                playTogether(scaleX, scaleY)
                duration = 400
                interpolator = DecelerateInterpolator()
                start()
            }

            // Fade out subtitle and ViewPager
            ObjectAnimator.ofFloat(tvSubtitle, View.ALPHA, 1f, 0f).setDuration(250).start()
            ObjectAnimator.ofFloat(layoutDots, View.ALPHA, 1f, 0f).setDuration(250).start()

            val titleFadeOut = ObjectAnimator.ofFloat(tvTitle, View.ALPHA, 1f, 0f).setDuration(250)
            titleFadeOut.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    tvTitle.text = "¡Hola, ${selectedProfile.name}!"
                    tvTitle.textSize = 38f
                    ObjectAnimator.ofFloat(tvTitle, View.ALPHA, 0f, 1f).setDuration(350).start()
                }
            })
            titleFadeOut.start()

            root.postDelayed({
                onProfileSelected(selectedProfile)
                dismiss()
            }, 1200)
        }

        // Configure ViewPager2 Carousel visual properties
        vpCarousel.offscreenPageLimit = 3
        (vpCarousel.getChildAt(0) as? RecyclerView)?.apply {
            clipChildren = false
            clipToPadding = false
            setPadding(120, 0, 120, 0)
        }

        // Apply 3D Carousel PageTransformer
        vpCarousel.setPageTransformer { page, position ->
            val absPos = Math.abs(position)
            val scale = 0.78f + (1f - 0.78f) * (1f - absPos.coerceAtMost(1f))
            val alpha = 0.40f + (1f - 0.40f) * (1f - absPos.coerceAtMost(1f))

            page.scaleX = scale
            page.scaleY = scale
            page.alpha = alpha
            page.translationZ = (1f - absPos.coerceAtMost(1f)) * 10f
        }

        // Dots indicator
        setupDots(layoutDots, profiles.size, activeIndex)
        vpCarousel.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                setupDots(layoutDots, profiles.size, position)
            }
        })

        vpCarousel.setCurrentItem(activeIndex, false)
    }

    private fun setupDots(container: LinearLayout, count: Int, selectedIndex: Int) {
        container.removeAllViews()
        if (count <= 1) return
        for (i in 0 until count) {
            val dot = View(context).apply {
                val size = if (i == selectedIndex) 24 else 12
                val params = LinearLayout.LayoutParams(size, 12).apply {
                    setMargins(8, 0, 8, 0)
                }
                layoutParams = params
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 6f
                    setColor(Color.parseColor(if (i == selectedIndex) "#E50914" else "#555555"))
                }
            }
            container.addView(dot)
        }
    }

    private class CarouselAdapter(
        private val items: List<UserProfile>,
        private val onItemClick: (UserProfile, View) -> Unit
    ) : RecyclerView.Adapter<CarouselAdapter.ViewHolder>() {

        class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
            val cardAvatar: MaterialCardView = view.findViewById(R.id.cardProfileAvatar)
            val imgAvatar: ImageView = view.findViewById(R.id.imgProfileAvatar)
            val tvName: TextView = view.findViewById(R.id.tvProfileName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_profile_carousel, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val profile = items[position]
            holder.tvName.text = profile.name

            if (profile.avatarType == AvatarType.PRESET) {
                val color = UserProfile.PRESET_AVATARS.find { it.first == profile.avatarValue }?.second ?: 0xFFE50914.toInt()
                holder.cardAvatar.setCardBackgroundColor(color)
                holder.imgAvatar.setImageDrawable(null)
            } else {
                holder.cardAvatar.setCardBackgroundColor(Color.TRANSPARENT)
                holder.imgAvatar.setImageURI(Uri.fromFile(File(profile.avatarValue)))
            }

            holder.itemView.setOnClickListener {
                onItemClick(profile, holder.itemView)
            }
        }

        override fun getItemCount() = items.size
    }
}
