/*
 * Copyright (c) 2003 Sun Microsystems, Inc. All Rights Reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 * 
 * - Redistribution of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 * 
 * - Redistribution in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 * 
 * Neither the name of Sun Microsystems, Inc. or the names of
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 * 
 * This software is provided "AS IS," without a warranty of any kind. ALL
 * EXPRESS OR IMPLIED CONDITIONS, REPRESENTATIONS AND WARRANTIES,
 * INCLUDING ANY IMPLIED WARRANTY OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE OR NON-INFRINGEMENT, ARE HEREBY EXCLUDED. SUN
 * MICROSYSTEMS, INC. ("SUN") AND ITS LICENSORS SHALL NOT BE LIABLE FOR
 * ANY DAMAGES SUFFERED BY LICENSEE AS A RESULT OF USING, MODIFYING OR
 * DISTRIBUTING THIS SOFTWARE OR ITS DERIVATIVES. IN NO EVENT WILL SUN OR
 * ITS LICENSORS BE LIABLE FOR ANY LOST REVENUE, PROFIT OR DATA, OR FOR
 * DIRECT, INDIRECT, SPECIAL, CONSEQUENTIAL, INCIDENTAL OR PUNITIVE
 * DAMAGES, HOWEVER CAUSED AND REGARDLESS OF THE THEORY OF LIABILITY,
 * ARISING OUT OF THE USE OF OR INABILITY TO USE THIS SOFTWARE, EVEN IF
 * SUN HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.
 * 
 * You acknowledge that this software is not designed or intended for use
 * in the design, construction, operation or maintenance of any nuclear
 * facility.
 * 
 * Sun gratefully acknowledges that this software was originally authored
 * and developed by Kenneth Bradley Russell and Christopher John Kline.
 */

package com.jogamp.opengl.impl.windows.wgl;

import com.jogamp.common.os.DynamicLookupHelper;
import java.nio.*;
import java.util.*;
import javax.media.nativewindow.*;
import javax.media.nativewindow.windows.*;
import javax.media.opengl.*;
import com.jogamp.common.JogampRuntimeException;
import com.jogamp.common.util.*;
import com.jogamp.opengl.impl.*;
import com.jogamp.nativewindow.impl.NullWindow;

public class WindowsWGLDrawableFactory extends GLDrawableFactoryImpl implements DynamicLookupHelper {
  private static final boolean VERBOSE = Debug.verbose();

  // Handle to GLU32.dll
  // FIXME: this should go away once we delete support for the C GLU library
  private long hglu32;

  // Handle to core OpenGL32.dll
  private long hopengl32;

  public WindowsWGLDrawableFactory() {
    super();

    // Register our GraphicsConfigurationFactory implementations
    // The act of constructing them causes them to be registered
    new WindowsWGLGraphicsConfigurationFactory();
    try {
      ReflectionUtil.createInstance("com.jogamp.opengl.impl.windows.wgl.awt.WindowsAWTWGLGraphicsConfigurationFactory",
                                  new Object[] {});
    } catch (JogampRuntimeException jre) { /* n/a .. */ }

    loadOpenGL32Library();

    try {
        sharedDrawable = new WindowsDummyWGLDrawable(this, null);
        WindowsWGLContext ctx  = (WindowsWGLContext) sharedDrawable.createContext(null);
        ctx.makeCurrent();
        canCreateGLPbuffer = ctx.getGL().isExtensionAvailable("GL_ARB_pbuffer");
        ctx.release();
        sharedContext = ctx;
    } catch (Throwable t) {
        throw new GLException("WindowsWGLDrawableFactory - Could not initialize shared resources", t);
    }
    if(null==sharedContext) {
        throw new GLException("WindowsWGLDrawableFactory - Shared Context is null");
    }
    if (DEBUG) {
      System.err.println("!!! SharedContext: "+sharedContext+", pbuffer supported "+canCreateGLPbuffer);
    }
  }

  WindowsDummyWGLDrawable sharedDrawable=null;
  WindowsWGLContext sharedContext=null;
  boolean canCreateGLPbuffer = false;

  protected final GLDrawableImpl getSharedDrawable() {
    return sharedDrawable; 
  }

  protected final GLContextImpl getSharedContext() {
    return sharedContext; 
  }

  protected void shutdown() {
     if (DEBUG) {
          System.err.println("!!! Shutdown Shared:");
          System.err.println("!!!          CTX     : "+sharedContext);
          System.err.println("!!!          Drawable: "+sharedDrawable);
          Exception e = new Exception("Debug");
          e.printStackTrace();
     }
    // don't free native resources from this point on,
    // since we might be in a critical shutdown hook sequence
     if(null!=sharedContext) {
        // may cause deadlock: sharedContext.destroy(); // implies release, if current
        sharedContext=null;
     }
     if(null!=sharedDrawable) {
        // may cause deadlock: sharedDrawable.destroy();
        sharedDrawable=null;
     }
  }

  public GLDrawableImpl createOnscreenDrawable(NativeWindow target) {
    if (target == null) {
      throw new IllegalArgumentException("Null target");
    }
    return new WindowsOnscreenWGLDrawable(this, target);
  }

  protected GLDrawableImpl createOffscreenDrawable(NativeWindow target) {
    if (target == null) {
      throw new IllegalArgumentException("Null target");
    }
    return new WindowsOffscreenWGLDrawable(this, target);
  }

  public boolean canCreateGLPbuffer(AbstractGraphicsDevice device) {
    return canCreateGLPbuffer;
  }

  protected GLDrawableImpl createGLPbufferDrawableImpl(final NativeWindow target) {
    if (target == null) {
      throw new IllegalArgumentException("Null target");
    }
    final List returnList = new ArrayList();
    final GLDrawableFactory factory = this;
    final WindowsWGLContext _sharedContext = sharedContext;
    final WindowsDummyWGLDrawable _sharedDrawable = sharedDrawable;
    Runnable r = new Runnable() {
        public void run() {
          GLContext lastContext = GLContext.getCurrent();
          if (lastContext != null) {
            lastContext.release();
          }
          _sharedContext.makeCurrent();
          WGLExt wglExt = _sharedContext.getWGLExt();
          try {
            GLDrawableImpl pbufferDrawable = new WindowsPbufferWGLDrawable(factory, target,
                                                                           _sharedDrawable,
                                                                           wglExt);
            returnList.add(pbufferDrawable);
          } finally {
            _sharedContext.release();
            if (lastContext != null) {
              lastContext.makeCurrent();
            }
          }
        }
      };
    maybeDoSingleThreadedWorkaround(r);
    return (GLDrawableImpl) returnList.get(0);
  }

  protected NativeWindow createOffscreenWindow(GLCapabilities capabilities, GLCapabilitiesChooser chooser, int width, int height) {
    AbstractGraphicsScreen screen = DefaultGraphicsScreen.createDefault();
    NullWindow nw = new NullWindow(WindowsWGLGraphicsConfigurationFactory.chooseGraphicsConfigurationStatic(
                                   capabilities, chooser, screen) );
    nw.setSize(width, height);
    return nw;
  }
 
  public GLContext createExternalGLContext() {
    return WindowsExternalWGLContext.create(this, null);
  }

  public boolean canCreateExternalGLDrawable(AbstractGraphicsDevice device) {
    return true;
  }

  public GLDrawable createExternalGLDrawable() {
    return WindowsExternalWGLDrawable.create(this, null);
  }

  public void loadOpenGL32Library() {
    if (hopengl32 == 0) {
      hopengl32 = WGL.LoadLibraryA("OpenGL32");
      if (DEBUG) {
        if (hopengl32 == 0) {
          System.err.println("WindowsWGLDrawableFactory: Could not load OpenGL32.dll - maybe an embedded device");
        }
      }
    }
  }

  public void loadGLULibrary() {
    if (hglu32 == 0) {
      hglu32 = WGL.LoadLibraryA("GLU32");
      if (hglu32 == 0) {
        throw new GLException("Error loading GLU32.DLL");
      }
    }
  }

  public long dynamicLookupFunction(String glFuncName) {
    long res = WGL.wglGetProcAddress(glFuncName);
    if (res == 0) {
      // It may happen that a driver doesn't return the OpenGL32 core function pointer
      // with wglGetProcAddress (e.g. NVidia GL 3.1) - hence we have to look harder.
      if (hopengl32 != 0) {
        res = WGL.GetProcAddress(hopengl32, glFuncName);
      }
    }
    if (res == 0) {
      // GLU routines aren't known to the OpenGL function lookup
      if (hglu32 != 0) {
        res = WGL.GetProcAddress(hglu32, glFuncName);
      }
    }
    return res;
  }

  static String wglGetLastError() {
    long err = WGL.GetLastError();
    String detail = null;
    switch ((int) err) {
      case WGL.ERROR_INVALID_PIXEL_FORMAT: detail = "ERROR_INVALID_PIXEL_FORMAT";       break;
      case WGL.ERROR_NO_SYSTEM_RESOURCES:  detail = "ERROR_NO_SYSTEM_RESOURCES";        break;
      case WGL.ERROR_INVALID_DATA:         detail = "ERROR_INVALID_DATA";               break;
      case WGL.ERROR_PROC_NOT_FOUND:       detail = "ERROR_PROC_NOT_FOUND";             break;
      case WGL.ERROR_INVALID_WINDOW_HANDLE:detail = "ERROR_INVALID_WINDOW_HANDLE";      break;
      default:                             detail = "(Unknown error code " + err + ")"; break;
    }
    return detail;
  }

  public boolean canCreateContextOnJava2DSurface(AbstractGraphicsDevice device) {
    return false;
  }

  public GLContext createContextOnJava2DSurface(Object graphics, GLContext shareWith)
    throws GLException {
    throw new GLException("Unimplemented on this platform");
  }

  //------------------------------------------------------
  // Gamma-related functionality
  //

  private static final int GAMMA_RAMP_LENGTH = 256;

  protected int getGammaRampLength() {
    return GAMMA_RAMP_LENGTH;
  }

  protected boolean setGammaRamp(float[] ramp) {
    short[] rampData = new short[3 * GAMMA_RAMP_LENGTH];
    for (int i = 0; i < GAMMA_RAMP_LENGTH; i++) {
      short scaledValue = (short) (ramp[i] * 65535);
      rampData[i] = scaledValue;
      rampData[i +     GAMMA_RAMP_LENGTH] = scaledValue;
      rampData[i + 2 * GAMMA_RAMP_LENGTH] = scaledValue;
    }

    long screenDC = WGL.GetDC(0);
    boolean res = WGL.SetDeviceGammaRamp(screenDC, ShortBuffer.wrap(rampData));
    WGL.ReleaseDC(0, screenDC);
    return res;
  }

  protected Buffer getGammaRamp() {
    ShortBuffer rampData = ShortBuffer.wrap(new short[3 * GAMMA_RAMP_LENGTH]);
    long screenDC = WGL.GetDC(0);
    boolean res = WGL.GetDeviceGammaRamp(screenDC, rampData);
    WGL.ReleaseDC(0, screenDC);
    if (!res) {
      return null;
    }
    return rampData;
  }

  protected void resetGammaRamp(Buffer originalGammaRamp) {
    if (originalGammaRamp == null) {
      // getGammaRamp failed earlier
      return;
    }
    long screenDC = WGL.GetDC(0);
    WGL.SetDeviceGammaRamp(screenDC, originalGammaRamp);
    WGL.ReleaseDC(0, screenDC);
  }
}
