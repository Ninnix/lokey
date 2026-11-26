package org.lokey.benchmark

import android.app.UiAutomation
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.io.DataInputStream
import java.io.DataOutputStream

@android.annotation.TargetApi(31)
class TouchInjection(automation:UiAutomation,apk:String):AutoCloseable {
    private val pipes=automation.executeShellCommandRw("/system/bin/env CLASSPATH=$apk /system/bin/app_process /system/bin org.lokey.benchmark.TouchInjector")
    private val input=DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(pipes[0]))
    private val output=DataOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]))
    init { check(input.read()==1) { "Direct touch injector could not start" } }
    fun send(action:Int,at:Long,down:Long,x:Float,y:Float) {
        output.writeInt(action);output.writeLong(at);output.writeLong(down);output.writeFloat(x);output.writeFloat(y);output.flush()
        check(input.read()==1) { "Direct touch injection failed" }
    }
    override fun close() { output.close();input.close() }
}

// Shell injection skips UiAutomation's per-tap window/frame synchronization.
object TouchInjector {
    @JvmStatic fun main(args:Array<String>) {
        Looper.prepareMainLooper()
        val type=try { Class.forName("android.hardware.input.InputManagerGlobal") }
            catch(_:ClassNotFoundException) { Class.forName("android.hardware.input.InputManager") }
        val manager=type.getMethod("getInstance").invoke(null)
        val inject=type.getMethod("injectInputEvent",InputEvent::class.java,Int::class.javaPrimitiveType)
        val input=DataInputStream(System.`in`.buffered())
        System.out.write(1);System.out.flush()
        while(true) {
            val action=try { input.readInt() } catch(_:java.io.EOFException) { break }
            val at=input.readLong();val down=input.readLong();val x=input.readFloat();val y=input.readFloat()
            val event=MotionEvent.obtain(down,at,action,x,y,0).apply { source=InputDevice.SOURCE_TOUCHSCREEN }
            val accepted=try { inject.invoke(manager,event,0)==true } finally { event.recycle() }
            System.out.write(if(accepted) 1 else 0);System.out.flush()
        }
    }
}
