package com.madhav.scanner.feature.benchmark

import com.madhav.scanner.core.bench.Stage
import org.json.JSONArray
import org.json.JSONObject

/**
 * DESIGN.md §6.6: results export as JSON and CSV. Raw samples are included, not just
 * percentiles, in both formats — re-deriving a different statistic later shouldn't require
 * re-running the whole config matrix.
 */
object BenchResultExporter {

    fun toJson(results: List<BenchResult>): String {
        val array = JSONArray()
        for (result in results) {
            val stages = JSONObject()
            for (stage in Stage.entries) {
                val stats = result.recorder.stats(stage) ?: continue
                stages.put(
                    stage.name,
                    JSONObject().apply {
                        put("mean", stats.mean)
                        put("stddev", stats.stddev)
                        put("min", stats.min)
                        put("max", stats.max)
                        put("p50", stats.p50)
                        put("p90", stats.p90)
                        put("p95", stats.p95)
                        put("p99", stats.p99)
                        put("raw_samples_ns", JSONArray(result.recorder.rawSamples(stage)))
                    },
                )
            }

            array.put(
                JSONObject().apply {
                    put("variant", result.config.variant.id)
                    put("delegate", result.config.delegate.name)
                    put("threads", result.config.threads)
                    put("cold_start_ns", result.coldStartNs)
                    put("stages", stages)
                },
            )
        }
        return array.toString(2)
    }

    /** One row per (config, stage, sample) — the flattest possible shape for raw samples. */
    fun toCsv(results: List<BenchResult>): String = buildString {
        appendLine("variant,delegate,threads,cold_start_ns,stage,sample_index,duration_ns")
        for (result in results) {
            for (stage in Stage.entries) {
                val samples = result.recorder.rawSamples(stage)
                samples.forEachIndexed { index, durationNs ->
                    appendLine(
                        "${result.config.variant.id},${result.config.delegate.name}," +
                            "${result.config.threads},${result.coldStartNs},${stage.name},$index,$durationNs",
                    )
                }
            }
        }
    }
}
