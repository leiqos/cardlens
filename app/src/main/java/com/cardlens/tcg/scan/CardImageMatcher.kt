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
        if (candidates.isEmpty()) return@coroutineScope Ranked(candidates, Int.MAX_VALUE, 0f)

        val scored = candidates.map { card ->
            async(Dispatchers.IO) {
                val url = card.imageSmall ?: card.imageLarge
                val fp = url?.let { fingerprintOf(it) }
                val dist = if (fp != null) PerceptualHash.distance(target, fp) else Int.MAX_VALUE
                card to dist
            }
        }.awaitAll().sortedBy { it.second }

        val best = scored.first().second
        val second = scored.getOrNull(1)?.second ?: Int.MAX_VALUE
        // Vertrauen: kleiner Abstand + deutlicher Vorsprung vor dem Zweitplatzierten.
        val confidence = when {
            best == Int.MAX_VALUE -> 0f
            best <= 16 && (second - best) >= 8 -> 1f
            best <= 24 -> 0.75f
            best <= 36 -> 0.5f
            else -> 0.25f
        }
        Ranked(scored.map { it.first }, best, confidence)
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
