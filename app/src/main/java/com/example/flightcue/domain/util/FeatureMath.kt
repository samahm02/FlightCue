package com.example.flightcue.domain.util

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Small math helpers used by feature extraction (same formulas as the script).
object FeatureMath {
    fun mean(x: DoubleArray)= if (x.isEmpty()) Double.NaN else x.average()
    fun std(x: DoubleArray): Double {
        if (x.isEmpty()) return Double.NaN
        val m=mean(x); var s=0.0; for (v in x) s+=(v-m)*(v-m); return sqrt(s / x.size)
    }
    fun min(x: DoubleArray)= if (x.isEmpty()) Double.NaN else x.minOrNull()!!
    fun max(x: DoubleArray)= if (x.isEmpty()) Double.NaN else x.maxOrNull()!!
    fun median(x: DoubleArray): Double {
        if (x.isEmpty()) return Double.NaN
        val y=x.copyOf().sortedArray(); val n=y.size
        return if (n%2==1) y[n/2] else 0.5*(y[n/2-1]+y[n/2])
    }
    private fun pct(x: DoubleArray, p: Double): Double {
        val y=x.copyOf().sortedArray()
        val pos=(p/100.0)*(y.size-1); val lo= floor(pos).toInt(); val hi= ceil(pos).toInt()
        if (lo==hi) return y[lo]; val w=pos-lo; return (1-w)*y[lo]+w*y[hi]
    }
    fun iqr(x: DoubleArray)= if (x.isEmpty()) Double.NaN else (pct(x,75.0)-pct(x,25.0))
    fun rms(x: DoubleArray)= if (x.isEmpty()) Double.NaN else sqrt(x.sumOf { it * it } / x.size)
    fun skew(x: DoubleArray): Double {
        if (x.size<3) return Double.NaN; val m=mean(x); val sd=std(x); if (sd==0.0||sd.isNaN()) return Double.NaN
        var s3=0.0; for (v in x) s3+=(v-m).pow(3.0); return (s3/x.size)/sd.pow(3.0)
    }
    fun kurtEx(x: DoubleArray): Double {
        if (x.size<4) return Double.NaN; val m=mean(x); val sd=std(x); if (sd==0.0||sd.isNaN()) return Double.NaN
        var s4=0.0; for (v in x) s4+=(v-m).pow(4.0); return (s4/x.size)/sd.pow(4.0)-3.0
    }
    fun halfDiff(x: DoubleArray)= if (x.size<4) Double.NaN else mean(x.sliceArray(x.size/2 until x.size)) - mean(x.sliceArray(0 until x.size/2))
    fun thirdDiff(x: DoubleArray): Double {
        if (x.size<6) return Double.NaN; val a=x.size/3; val b=2*x.size/3
        return mean(x.sliceArray(b until x.size)) - mean(x.sliceArray(0 until a))
    }
    fun peakCount(x: DoubleArray, k: Double=2.0): Double {
        if (x.isEmpty()) return 0.0; val sd=std(x); if (sd==0.0||sd.isNaN()) return 0.0
        val m=mean(x); val thr=k*sd; return x.count { abs(it-m) > thr }.toDouble()
    }
    fun ema(x: DoubleArray, alpha: Double): DoubleArray {
        if (x.isEmpty()) return x; val y=DoubleArray(x.size); y[0]=x[0]; for (i in 1 until x.size) y[i]=alpha*x[i]+(1-alpha)*y[i-1]; return y
    }
    fun zcrPerSec(x: DoubleArray, hz: Double, thr: Double): Double {
        if (x.size<2) return 0.0
        fun sign(v: Double)= when { abs(v) <=thr -> 0; v>0 -> 1; else -> -1 }
        var c=0; var p=sign(x[0]); for (i in 1 until x.size){ val s=sign(x[i]); if (s!=0 && p!=0 && s!=p) c++; p=s }
        return c/(x.size/hz)
    }
    fun runLenSec(mask: BooleanArray, hz: Double): Double {
        var best=0; var cur=0; for (v in mask){ if (v){ cur++; if (cur>best) best=cur } else cur=0 }; return best/hz
    }
    fun slope(t: DoubleArray, y: DoubleArray): Double {
        val n=minOf(t.size,y.size); if (n<2) return Double.NaN
        val tm=t.average(); val ym=y.average(); var num=0.0; var den=0.0
        for (i in 0 until n){ val dt=t[i]-tm; num+=dt*(y[i]-ym); den+=dt*dt }
        return if (den==0.0) Double.NaN else num/den
    }

    // simple FFT-based power spectrum (Hann), returns (freq, power)
    fun rfftPower(xIn: DoubleArray, fs: Double): Pair<DoubleArray,DoubleArray> {
        var n=xIn.size; if (n<8) return DoubleArray(0) to DoubleArray(0)
        var m=1; while (m<n) m = m shl 1
        val nFFT=m
        val mean=xIn.average()
        val real=DoubleArray(nFFT){ i -> val v= if (i<n) xIn[i]-mean else 0.0; val w=0.5*(1 - cos(2.0*Math.PI*i/(nFFT-1))); v*w }
        val imag=DoubleArray(nFFT)
        var step=1
        while (step<nFFT){
            val jump=step shl 1; val delta=Math.PI/step
            for (k in 0 until step){
                val wr= cos(delta*k); val wi= -sin(delta*k)
                var i=k
                while (i<nFFT){
                    val j=i+step
                    val tr=wr*real[j]-wi*imag[j]; val ti=wr*imag[j]+wi*real[j]
                    real[j]=real[i]-tr; imag[j]=imag[i]-ti
                    real[i]+=tr; imag[i]+=ti
                    i+=jump
                }
            }
            step=jump
        }
        val outN=nFFT/2+1
        val f=DoubleArray(outN){ i -> i*fs/nFFT }
        val p=DoubleArray(outN){ i -> val r=real[i]; val im=imag[i]; r*r+im*im }
        return f to p
    }
    fun relBandPowers(x: DoubleArray, fs: Double, bands: Array<Pair<Double,Double>>): Map<String,Double> {
        val (f,p)=rfftPower(x,fs); if (f.isEmpty()) return bands.associate { k(it.first,it.second) to Double.NaN }
        val tot=p.sum(); if (tot<=0.0) return bands.associate { k(it.first,it.second) to Double.NaN }
        val out=LinkedHashMap<String,Double>(bands.size)
        for ((lo,hi) in bands){
            var s=0.0; for (i in f.indices) if (f[i]>=lo && f[i]<hi) s+=p[i]
            out[k(lo,hi)]=s/tot
        }
        return out
    }
    private fun k(lo: Double, hi: Double) = "pow_${"%.1f".format(lo)}_${"%.1f".format(hi)}"
}