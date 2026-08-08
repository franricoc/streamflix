package com.streamflixreborn.streamflix.providers

/**
 * Annotation para marcar una clase como Provider de Streamflix.
 * El ProviderRegistry detectará automáticamente todas las clases con esta annotation.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class StreamflixProvider(
    val name: String,
    val language: String,
    val movies: Boolean = true,
    val tvShows: Boolean = true
)
