package com.streamflixreborn.streamflix.ui

import android.content.Context
import com.streamflixreborn.streamflix.models.UserProfile

class CreateProfileTvDialog(
    private val context: Context,
    private val existingProfile: UserProfile? = null,
    private val onProfileSaved: (UserProfile) -> Unit,
    private val onProfileDeleted: ((String) -> Unit)? = null
) {
    fun show() {
        ProfileSelectorTvDialog.showCreateProfileInputDialog(context, onProfileSaved)
    }
}
