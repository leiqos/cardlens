package com.cardlens.tcg.scan

import android.graphics.BitmapFactory
import com.cardlens.tcg.model.TcgCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * Visueller Karten-Abgleich: vergleicht das gescannte Kartenbild per
 * Perceptual Hash mit den Bildern der Treffer-Kandidaten und sortiert sie
 * nach visueller Aehnlichkeit. So wird aus vielen namensgleichen Drucken
 * genau die Edition ausgewaehlt, die der Nutzer in der Hand haelt —
 * unabhaengig davon, ob OCR den Set-Code lesen konnte.
 *
 * Kandidaten-Hashes werden je Bild-URL gecacht (App-Sitzung), damit wiederholte
 * Scans derselben Karten nicht erneut laden.
 */
class CardImageMatcher(private val http: OkHttpClient) {

    private val cache = ConcurrentHashMap<String, PerceptualHash.Fingerprint>()

    data class Ranked(
        val cards: List<TcgCard>,
        val bestDistance: Int,
        val runnerUpDistance: Int,
        /** Vertrauen 0..1: hoher Wert = klarer visueller Treffer. */
        val confidence: Float
    )

    /**
     * Sortiert [candidates] nach visueller Naehe zum Fingerabdruck [target]
     * des gescannten Kartenbilds. Kandidaten ohne ladbares Bild rutschen ans
     * Ende. Ist kein Vergleich moeglich, bleibt die Originalreihenfolge
     * (confidence 0).
     */
    suspend fun rank(
        target: PerceptualHash.Fingerprint,
        candidates: List<TcgCard>
    ): Ranked = coroutineScope {
        if (candidates.isEmpty()) {
            return@coroutineScope Ranked(candidates, Int.MAX_VALUE, Int.MAX_VALUE, 0f)
        }

        val scored = candidates.map { card ->
            async(Dispatchers.IO) {
                // Variant discrimination depends on footer/frame detail; use
                // the largest catalogue image whenever one exists.
                val url = card.imageLarge ?: card.imageSmall
                val fp = url?.let { fingerprintOf(it) }
                val dist = if (fp != null) PerceptualHash.distance(target, fp) else Int.MAX_VALUE
                card to dist
            }
        }.awaitAll().sortedBy { it.second }

        val best = scored.first().second
        val second = scored.getOrNull(1)?.second ?: Int.MAX_VALUE
        val compared = scored.count { it.second != Int.MAX_VALUE }
        val confidence = VisualMatchConfidence.score(best, second, compared)
        Ranked(scored.map { it.first }, best, second, confidence)
    }

    private suspend fun fingerprintOf(url: String): PerceptualHash.Fingerprint? {
        cache[url]?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(url).build()
                http.newCall(request).execute().use { response ->
                    val bytes = response.body?.bytes() ?: return@use null
                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                        ?: return@use null
                    PerceptualHash.fingerprint(bmp).also {
                        bmp.recycle()
                        cache[url] = it
                    }
                }
            }.getOrNull()
        }
    }
}

/** Pure, unit-testable calibration for visual ranking confidence. */
internal object VisualMatchConfidence {
    fun score(best: Int, second: Int, compared: Int): Float {
        if (best == Int.MAX_VALUE || compared <= 0) return 0f
        if (compared == 1) return when {
            best <= 58 -> 0.9f
            best <= 82 -> 0.7f
            best <= 105 -> 0.45f
            else -> 0.1f
        }

        val gap = (second - best).coerceAtLeast(0)
        return when {
            best <= 64 && gap >= 28 -> 0.95f
            best <= 80 && gap >= 18 -> 0.8f
            best <= 96 && gap >= 12 -> 0.65f
            best <= 112 && gap >= 8 -> 0.45f
            best <= 128 -> 0.15f
            else -> 0f
        }
    }
}
