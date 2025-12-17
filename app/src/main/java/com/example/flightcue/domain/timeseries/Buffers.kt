package com.example.flightcue.domain.timeseries

// Simple ring buffers for raw sensor streams (timestamps in seconds since boot).
class AccelBuffer(private val cap: Int = 60 * 60 * 120) {
    private val t=DoubleArray(cap); private val ax=DoubleArray(cap); private val ay=DoubleArray(cap); private val az=DoubleArray(cap)
    private var n=0; private var head=0
    fun push(ts: Double, x: Double, y: Double, z: Double) {
        t[head]=ts; ax[head]=x; ay[head]=y; az[head]=z; head=(head+1)%cap; n=minOf(cap,n+1)
    }
    fun snapshot(): AccelSlice {
        val outN=n; val outT=DoubleArray(outN); val outX=DoubleArray(outN); val outY=DoubleArray(outN); val outZ=DoubleArray(outN)
        val start=(head-outN+cap)%cap
        for (i in 0 until outN) { val idx=(start+i)%cap; outT[i]=t[idx]; outX[i]=ax[idx]; outY[i]=ay[idx]; outZ[i]=az[idx] }
        return AccelSlice(outT,outX,outY,outZ)
    }
}
class BaroBuffer(private val cap: Int = 60 * 60 * 10) {
    private val t=DoubleArray(cap); private val p=DoubleArray(cap)
    private var n=0; private var head=0
    fun push(ts: Double, pressureHpa: Double) { t[head]=ts; p[head]=pressureHpa; head=(head+1)%cap; n=minOf(cap,n+1) }
    fun snapshot(): BaroSlice {
        val outN=n; val outT=DoubleArray(outN); val outP=DoubleArray(outN)
        val start=(head-outN+cap)%cap
        for (i in 0 until outN) { val idx=(start+i)%cap; outT[i]=t[idx]; outP[i]=p[idx] }
        return BaroSlice(outT,outP)
    }
}
data class AccelSlice(val t: DoubleArray, val ax: DoubleArray, val ay: DoubleArray, val az: DoubleArray)
data class BaroSlice (val t: DoubleArray, val p: DoubleArray)
