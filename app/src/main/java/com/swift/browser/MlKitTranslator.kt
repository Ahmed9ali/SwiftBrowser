package com.swift.browser

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

class MlKitTranslator(
    private val sourceLang: String, // e.g., "zh", "hi"
    private val targetLang: String  // e.g., "en"
) {
    private val translator: Translator by lazy {
        // Map generic codes to ML Kit constants
        val source = when(sourceLang.lowercase()) {
            "zh" -> TranslateLanguage.CHINESE
            "hi" -> TranslateLanguage.HINDI
            "es" -> TranslateLanguage.SPANISH
            "fr" -> TranslateLanguage.FRENCH
            "ru" -> TranslateLanguage.RUSSIAN
            "ja" -> TranslateLanguage.JAPANESE
            "ko" -> TranslateLanguage.KOREAN
            "ar" -> TranslateLanguage.ARABIC
            "pt" -> TranslateLanguage.PORTUGUESE
            "de" -> TranslateLanguage.GERMAN
            else -> TranslateLanguage.ENGLISH // fallback
        }
        val target = when(targetLang.lowercase()) {
            "zh" -> TranslateLanguage.CHINESE
            "hi" -> TranslateLanguage.HINDI
            "es" -> TranslateLanguage.SPANISH
            "fr" -> TranslateLanguage.FRENCH
            "ru" -> TranslateLanguage.RUSSIAN
            "ja" -> TranslateLanguage.JAPANESE
            "ko" -> TranslateLanguage.KOREAN
            "ar" -> TranslateLanguage.ARABIC
            "pt" -> TranslateLanguage.PORTUGUESE
            "de" -> TranslateLanguage.GERMAN
            else -> TranslateLanguage.ENGLISH // fallback
        }

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(source)
            .setTargetLanguage(target)
            .build()
        Translation.getClient(options)
    }

    // Downloads the language file (e.g., 30MB for Chinese) to the device
    suspend fun prepareModel() {
        val conditions = DownloadConditions.Builder()
            .requireWifi() // Only download over WiFi to save data
            .build()
        translator.downloadModelIfNeeded(conditions).await()
    }

    suspend fun translate(text: String): String {
        return try {
            translator.translate(text).await()
        } catch (e: Exception) {
            text // Return original if it fails
        }
    }

    fun close() {
        translator.close()
    }
}