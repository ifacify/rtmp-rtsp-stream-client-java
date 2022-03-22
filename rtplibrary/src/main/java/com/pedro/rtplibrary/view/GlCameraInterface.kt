package com.pedro.rtplibrary.view

import android.content.Context
import android.graphics.Point
import android.graphics.SurfaceTexture
import android.graphics.SurfaceTexture.OnFrameAvailableListener
import android.os.Build
import android.view.Surface
import androidx.annotation.RequiresApi
import com.pedro.encoder.input.gl.SurfaceManager
import com.pedro.encoder.input.gl.render.MainRender
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.encoder.input.video.FpsLimiter
import java.util.concurrent.Semaphore

/**
 * Created by pedro on 14/3/22.
 */
@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
class GlCameraInterface(private val context: Context) : Runnable, OnFrameAvailableListener {

  private var thread: Thread? = null
  private var frameAvailable = false
  var running = false
  private var initialized = false
  private val surfaceManager = SurfaceManager()
  private val surfaceManagerEncoder = SurfaceManager()
  private val surfaceManagerPreview = SurfaceManager()
  private var managerRender: MainRender? = null
  private val semaphore = Semaphore(0)
  private val sync = Object()
  private var encoderWidth = 0
  private var encoderHeight = 0
  private var streamOrientation = 0
  private var previewWidth = 0
  private var previewHeight = 0
  private var previewOrientation = 0
  private var isPortrait = false
  private val fpsLimiter = FpsLimiter()

  fun init() {
    if (!initialized) managerRender = MainRender()
    managerRender?.setCameraFlip(false, false)
    initialized = true
  }

  fun setEncoderSize(width: Int, height: Int) {
    encoderWidth = width
    encoderHeight = height
  }

  fun getEncoderSize(): Point {
    return Point(encoderWidth, encoderHeight)
  }

  fun setFps(fps: Int) {
    fpsLimiter.setFPS(fps)
  }

  fun getSurfaceTexture(): SurfaceTexture {
    return managerRender!!.getSurfaceTexture()
  }

  fun getSurface(): Surface {
    return managerRender!!.getSurface()
  }

  fun addMediaCodecSurface(surface: Surface) {
    synchronized(sync) {
      if (surfaceManager.isReady) {
        surfaceManagerEncoder.release()
        surfaceManagerEncoder.eglSetup(surface, surfaceManager)
      }
    }
  }

  fun removeMediaCodecSurface() {
    synchronized(sync) {
      surfaceManagerEncoder.release()
    }
  }

  fun start() {
    synchronized(sync) {
      thread = Thread(this)
      running = true
      thread?.start()
      semaphore.acquireUninterruptibly()
    }
  }

  fun stop() {
    synchronized(sync) {
      running = false
      thread?.interrupt()
      try {
        thread?.join(100)
      } catch (e: InterruptedException) {
        thread?.interrupt()
      }
      thread = null
      surfaceManagerEncoder.release()
      surfaceManager.release()
    }
  }

  override fun run() {
    surfaceManager.release()
    surfaceManager.eglSetup()
    surfaceManager.makeCurrent()
    managerRender?.initGl(context, encoderWidth, encoderHeight, encoderWidth, encoderHeight)
    managerRender?.getSurfaceTexture()?.setOnFrameAvailableListener(this)
    semaphore.release()
    try {
      while (running) {
        if (frameAvailable) {
          frameAvailable = false
          surfaceManager.makeCurrent()
          managerRender?.updateFrame()
          managerRender?.drawOffScreen()
          managerRender?.drawScreen(encoderWidth, encoderHeight, false, 0, 0,
            true, false, false)
          surfaceManager.swapBuffer()

          synchronized(sync) {
            val limitFps = fpsLimiter.limitFPS()
            // render VideoEncoder (stream and record)
            if (surfaceManagerEncoder.isReady && !limitFps) {
              val w =  encoderWidth
              val h =  encoderHeight
              surfaceManagerEncoder.makeCurrent()
              managerRender?.drawScreenEncoder(w, h, isPortrait, false, 0, streamOrientation,
                false, false, false)
              surfaceManagerEncoder.swapBuffer()
            }
            // render preview
            if (surfaceManagerPreview.isReady && !limitFps) {
              val w =  if (previewWidth == 0) encoderWidth else previewWidth
              val h =  if (previewHeight == 0) encoderHeight else previewHeight
              val eW = if (previewOrientation == 0) encoderHeight else encoderWidth
              val eH = if (previewOrientation == 0) encoderWidth else encoderHeight
              surfaceManagerPreview.makeCurrent()
              managerRender?.drawScreenPreview(w, h, eW, eH, true, 0, previewOrientation,
                true, false, false)
              surfaceManagerPreview.swapBuffer()
            }
          }
        }
      }
    } catch (ignore: InterruptedException) {
      Thread.currentThread().interrupt()
    } finally {
      managerRender?.release()
      surfaceManagerEncoder.release()
      surfaceManager.release()
    }
  }

  override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
    synchronized(sync) {
      frameAvailable = true
      sync.notifyAll()
    }
  }

  fun attachPreview(surface: Surface) {
    synchronized(sync) {
      if (surfaceManager.isReady) {
        isPortrait = CameraHelper.isPortrait(context)
        surfaceManagerPreview.release()
        surfaceManagerPreview.eglSetup(surface, surfaceManager)
      }
    }
  }

  fun deAttachPreview() {
    synchronized(sync) {
      surfaceManagerPreview.release()
    }
  }

  fun setStreamOrientation(orientation: Int) {
    this.streamOrientation = orientation
  }

  fun setPreviewResolution(width: Int, height: Int) {
    this.previewWidth = width
    this.previewHeight = height
  }

  fun setPreviewOrientation(orientation: Int) {
    this.previewOrientation = orientation
  }
}