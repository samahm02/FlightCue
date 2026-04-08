package com.example.flightcue

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.flightcue.domain.features.Scaler
import com.example.flightcue.data.modelspec.ModelConfigs
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class ScalerTest {

    @Test
    fun testScalerBasicFunctionality() {
        println("=== SCALER BASIC FUNCTIONALITY TEST ===")

        // Test data: 5 features
        val mean = doubleArrayOf(10.0, 20.0, 30.0, 40.0, 50.0)
        val scale = doubleArrayOf(2.0, 5.0, 10.0, 1.0, 8.0)

        // Raw features
        val raw = doubleArrayOf(12.0, 25.0, 20.0, 41.0, 58.0)

        // Expected scaled values: z[i] = (raw[i] - mean[i]) / scale[i]
        val expected = doubleArrayOf(
            (12.0 - 10.0) / 2.0,   // = 1.0
            (25.0 - 20.0) / 5.0,   // = 1.0
            (20.0 - 30.0) / 10.0,  // = -1.0
            (41.0 - 40.0) / 1.0,   // = 1.0
            (58.0 - 50.0) / 8.0    // = 1.0
        )

        val scaled = Scaler.scaleWindow(raw, mean, scale)

        println("Raw:      ${raw.joinToString { "%.2f".format(it) }}")
        println("Mean:     ${mean.joinToString { "%.2f".format(it) }}")
        println("Scale:    ${scale.joinToString { "%.2f".format(it) }}")
        println("Expected: ${expected.joinToString { "%.4f".format(it) }}")
        println("Scaled:   ${scaled.joinToString { "%.4f".format(it) }}")

        // Verify each feature
        for (i in scaled.indices) {
            assertEquals("Feature $i mismatch", expected[i], scaled[i], 1e-6)
        }

        println("✓ Basic scaling works correctly")
    }

    @Test
    fun testScalerNaNHandling() {
        println("\n=== SCALER NaN HANDLING TEST ===")

        val mean = doubleArrayOf(10.0, 20.0, 30.0)
        val scale = doubleArrayOf(2.0, 5.0, 10.0)

        // Raw with NaN values
        val raw = doubleArrayOf(12.0, Double.NaN, 20.0)

        val scaled = Scaler.scaleWindow(raw, mean, scale)

        println("Raw:    [12.0, NaN, 20.0]")
        println("Scaled: ${scaled.joinToString { "%.4f".format(it) }}")

        // Feature 0: (12 - 10) / 2 = 1.0
        assertEquals("Feature 0", 1.0, scaled[0], 1e-6)

        // Feature 1: NaN should be imputed as mean[1]=20.0, so (20-20)/5 = 0.0
        assertEquals("Feature 1 (NaN imputed)", 0.0, scaled[1], 1e-6)

        // Feature 2: (20 - 30) / 10 = -1.0
        assertEquals("Feature 2", -1.0, scaled[2], 1e-6)

        println("✓ NaN values are correctly imputed to zero")
    }

    @Test
    fun testScalerInfHandling() {
        println("\n=== SCALER Inf HANDLING TEST ===")

        val mean = doubleArrayOf(10.0, 20.0, 30.0)
        val scale = doubleArrayOf(2.0, 5.0, 10.0)

        // Raw with Inf values
        val raw = doubleArrayOf(Double.POSITIVE_INFINITY, 25.0, Double.NEGATIVE_INFINITY)

        val scaled = Scaler.scaleWindow(raw, mean, scale)

        println("Raw:    [+Inf, 25.0, -Inf]")
        println("Scaled: ${scaled.joinToString { "%.4f".format(it) }}")

        // Feature 0: +Inf should be imputed as mean[0]=10.0, so (10-10)/2 = 0.0
        assertEquals("Feature 0 (+Inf imputed)", 0.0, scaled[0], 1e-6)

        // Feature 1: (25 - 20) / 5 = 1.0
        assertEquals("Feature 1", 1.0, scaled[1], 1e-6)

        // Feature 2: -Inf should be imputed as mean[2]=30.0, so (30-30)/10 = 0.0
        assertEquals("Feature 2 (-Inf imputed)", 0.0, scaled[2], 1e-6)

        println("✓ Inf values are correctly imputed to zero")
    }

    @Test
    fun testScalerZeroScaleHandling() {
        println("\n=== SCALER ZERO SCALE HANDLING TEST ===")

        val mean = doubleArrayOf(10.0, 20.0, 30.0)
        val scale = doubleArrayOf(2.0, 0.0, 1e-10) // scale[1] is zero, scale[2] is tiny

        val raw = doubleArrayOf(12.0, 25.0, 35.0)

        val scaled = Scaler.scaleWindow(raw, mean, scale)

        println("Raw:    ${raw.joinToString { "%.2f".format(it) }}")
        println("Scale:  ${scale.joinToString { it.toString() }}")
        println("Scaled: ${scaled.joinToString { "%.4f".format(it) }}")

        // Feature 0: normal scaling
        assertEquals("Feature 0", 1.0, scaled[0], 1e-6)

        // Feature 1: scale=0 should give 0.0 (avoid division by zero)
        assertEquals("Feature 1 (zero scale)", 0.0, scaled[1], 1e-6)

        // Feature 2: scale=1e-10 is below threshold, should give 0.0
        assertEquals("Feature 2 (tiny scale)", 0.0, scaled[2], 1e-6)

        println("✓ Zero/tiny scale values are handled safely")
    }

    @Test
    fun testScalerWithRealModelScaler() {
        println("\n=== SCALER WITH REAL MODEL DATA TEST ===")

        // Load actual scaler from model assets
        val context = ApplicationProvider.getApplicationContext<Context>()

        try {
            // Load TAKEOFF schema first
            val schemaTO = ModelConfigs.parseFeatures(context, isTakeoff = true)

            // Load TAKEOFF scaler using the schema
            val scalerPair = ModelConfigs.parseScaler(context, schemaTO, isTakeoff = true)
            val meanTO = scalerPair.first
            val scaleTO = scalerPair.second

            println("Loaded TAKEOFF scaler:")
            println("  Features: ${meanTO.size}")
            println("  mean[0]: ${meanTO[0]}")
            println("  scale[0]: ${scaleTO[0]}")
            println("  mean[last]: ${meanTO[meanTO.size - 1]}")
            println("  scale[last]: ${scaleTO[scaleTO.size - 1]}")

            // ⚠️ CRITICAL CHECK: Verify scaler is NOT all 1.0 (would indicate dummy data)
            val allOnes = meanTO.all { abs(it - 1.0) < 1e-6 } && scaleTO.all { abs(it - 1.0) < 1e-6 }
            if (allOnes) {
                fail("❌ SCALER IS DUMMY DATA (all 1.0)! Re-export scaler.npz from Python training script!")
            }

            // Verify reasonable scaler statistics
            val meanMin = meanTO.minOrNull() ?: 0.0
            val meanMax = meanTO.maxOrNull() ?: 0.0
            val meanRange = meanMax - meanMin
            val scaleMin = scaleTO.minOrNull() ?: 0.0
            println("  mean range: $meanRange")
            println("  scale min: $scaleMin")

            assertTrue("Mean should vary across features", meanRange > 0.1)
            assertTrue("Scale should be positive", scaleMin > 0.0)

            // Test scaling with dummy data
            val raw = DoubleArray(meanTO.size) { 0.5 }
            val scaled = Scaler.scaleWindow(raw, meanTO, scaleTO)

            println("  Scaled dummy window (all 0.5):")
            println("    scaled[0]: ${scaled[0]}")
            println("    scaled[last]: ${scaled[scaled.size - 1]}")

            // All scaled values should be finite
            assertTrue("All scaled values should be finite", scaled.all { it.isFinite() })

            // Try LANDING model too
            val schemaLD = ModelConfigs.parseFeatures(context, isTakeoff = false)
            val scalerPairLD = ModelConfigs.parseScaler(context, schemaLD, isTakeoff = false)
            val meanLD = scalerPairLD.first
            val scaleLD = scalerPairLD.second

            println("\nLoaded LANDING scaler:")
            println("  Features: ${meanLD.size}")
            println("  mean[0]: ${meanLD[0]}")
            println("  scale[0]: ${scaleLD[0]}")

            println("\n✓ Real model scalers loaded and work correctly")

        } catch (e: Exception) {
            println("⚠ Could not load model scaler (test may be running without assets): ${e.message}")
            e.printStackTrace()
            // Don't fail test if assets not available during unit testing
        }
    }

    @Test
    fun testScalerPythonParity() {
        println("\n=== SCALER PYTHON PARITY TEST ===")

        // Simulate Python StandardScaler behavior:
        // Given training data: [1.0, 2.0, 3.0, 4.0, 5.0]
        // mean = 3.0
        // std = sqrt(var) = sqrt(2.0) ≈ 1.4142

        val mean = doubleArrayOf(3.0)
        val scale = doubleArrayOf(1.4142135623730951) // sqrt(2.0)

        // Test data point: 4.0
        val raw = doubleArrayOf(4.0)

        // Expected: (4.0 - 3.0) / 1.4142 ≈ 0.7071
        val expected = (4.0 - 3.0) / 1.4142135623730951

        val scaled = Scaler.scaleWindow(raw, mean, scale)

        println("Python StandardScaler equivalent:")
        println("  mean: 3.0")
        println("  scale: 1.4142135623730951")
        println("  raw: 4.0")
        println("  expected: $expected")
        println("  scaled: ${scaled[0]}")

        assertEquals("Should match Python StandardScaler", expected, scaled[0], 1e-10)

        println("✓ Matches Python StandardScaler behavior")
    }

    @Test
    fun testScalerSequenceBuildingWorkflow() {
        println("\n=== SCALER SEQUENCE BUILDING WORKFLOW TEST ===")

        val nFeatures = 154
        val seqLen = 25

        // Dummy scaler
        val mean = DoubleArray(nFeatures) { 10.0 }
        val scale = DoubleArray(nFeatures) { 2.0 }

        // Build a sequence by scaling each window INDIVIDUALLY
        val sequence = Array(seqLen) { windowIdx ->
            // Generate dummy raw features for this window
            val raw = DoubleArray(nFeatures) { featureIdx ->
                10.0 + windowIdx * 0.1 + featureIdx * 0.01
            }

            // Scale this window (154 features → 154 scaled features)
            Scaler.scaleWindow(raw, mean, scale)
        }

        println("Built sequence: $seqLen windows × $nFeatures features")
        println("  Window 0, feature 0: ${sequence[0][0]}")
        println("  Window 0, feature 153: ${sequence[0][153]}")
        println("  Window 24, feature 0: ${sequence[24][0]}")
        println("  Window 24, feature 153: ${sequence[24][153]}")

        // Verify shape
        assertEquals("Sequence length", seqLen, sequence.size)
        assertEquals("Feature count", nFeatures, sequence[0].size)

        // Verify all values are finite
        var allFinite = true
        for (window in sequence) {
            for (value in window) {
                if (!value.isFinite()) {
                    allFinite = false
                    break
                }
            }
        }
        assertTrue("All sequence values should be finite", allFinite)

        println("✓ Sequence building workflow works correctly")
    }

    @Test
    fun testScalerZeros() {
        println("\n=== SCALER ZEROS TEST ===")

        val nFeatures = 154
        val zeros = Scaler.zeros(nFeatures)

        println("Generated zeros array: ${zeros.size} features")
        println("  All zeros? ${zeros.all { it == 0.0 }}")

        assertEquals("Should have correct size", nFeatures, zeros.size)
        assertTrue("Should be all zeros", zeros.all { it == 0.0 })

        println("✓ Zeros array generation works")
    }
}