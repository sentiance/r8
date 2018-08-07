// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.debug;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class LocalEndTestDump implements Opcodes {

  public static byte[] dump() {

    ClassWriter cw = new ClassWriter(0);
    MethodVisitor mv;

    cw.visit(
        V1_8,
        ACC_PUBLIC + ACC_SUPER,
        "com/android/tools/r8/debug/LocalEndTest",
        null,
        "java/lang/Object",
        null);

    cw.visitSource("LocalEndTest.java", null);

    {
      mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
      mv.visitCode();
      Label l0 = new Label();
      mv.visitLabel(l0);
      mv.visitLineNumber(6, l0);
      mv.visitVarInsn(ALOAD, 0);
      mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
      mv.visitInsn(RETURN);
      Label l1 = new Label();
      mv.visitLabel(l1);
      mv.visitLocalVariable("this", "Lcom/android/tools/r8/debug/LocalEndTest;", null, l0, l1, 0);
      mv.visitMaxs(1, 1);
      mv.visitEnd();
    }
    {
      mv = cw.visitMethod(ACC_PUBLIC, "foo", "()V", null, null);
      mv.visitCode();
      Label l0 = new Label();
      Label l1 = new Label();
      Label l2 = new Label();
      mv.visitTryCatchBlock(l0, l1, l2, "java/lang/Throwable");
      Label l3 = new Label();
      mv.visitLabel(l3);
      mv.visitLineNumber(10, l3);
      mv.visitIntInsn(BIPUSH, 42);
      mv.visitVarInsn(ISTORE, 1);
      mv.visitLabel(l0);
      mv.visitLineNumber(12, l0);
      mv.visitVarInsn(ILOAD, 1); // push x on the stack for later use in the join block.
      mv.visitVarInsn(ALOAD, 0);
      mv.visitMethodInsn(
          INVOKESPECIAL, "com/android/tools/r8/debug/LocalEndTest", "bar", "()V", false);
      Label l4 = new Label();
      mv.visitLabel(l4);
      mv.visitLineNumber(13, l4);
      mv.visitIntInsn(BIPUSH, 7);
      mv.visitVarInsn(ISTORE, 1);
      mv.visitLabel(l1);
      mv.visitLineNumber(14, l1);
      Label l5 = new Label();
      mv.visitJumpInsn(GOTO, l5);
      mv.visitLabel(l2);
      mv.visitFrame(
          Opcodes.F_FULL,
          2,
          new Object[] {"com/android/tools/r8/debug/LocalEndTest", Opcodes.INTEGER},
          1,
          new Object[] {"java/lang/Throwable"});
      mv.visitVarInsn(ASTORE, 2);
      mv.visitVarInsn(ILOAD, 1); // push x on the stack again (should be same as above).
      // mv.visitInsn(ICONST_0);
      mv.visitLabel(l5);
      mv.visitLineNumber(16, l5);
      // mv.visitFrame(Opcodes.F_CHOP, 1, null, 0, null);
      mv.visitFrame(
          Opcodes.F_FULL,
          1,
          new Object[] {"com/android/tools/r8/debug/LocalEndTest"},
          1,
          new Object[] {Opcodes.INTEGER});
      // Load the on-stack copy of x
      mv.visitVarInsn(ISTORE, 1);
      Label l6 = new Label();
      mv.visitLabel(l6);
      mv.visitLineNumber(17, l6);
      mv.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
      mv.visitVarInsn(ILOAD, 1);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
      Label l7 = new Label();
      mv.visitLabel(l7);
      mv.visitLineNumber(18, l7);
      mv.visitInsn(RETURN);
      Label l8 = new Label();
      mv.visitLabel(l8);
      mv.visitLocalVariable("x", "I", null, l0, l5, 1);
      mv.visitLocalVariable("this", "Lcom/android/tools/r8/debug/LocalEndTest;", null, l3, l8, 0);
      mv.visitLocalVariable("y", "I", null, l6, l8, 1);
      mv.visitMaxs(2, 3);
      mv.visitEnd();
    }
    {
      mv = cw.visitMethod(ACC_PRIVATE, "bar", "()V", null, null);
      mv.visitCode();
      Label l0 = new Label();
      mv.visitLabel(l0);
      mv.visitLineNumber(22, l0);
      mv.visitInsn(RETURN);
      Label l1 = new Label();
      mv.visitLabel(l1);
      mv.visitLocalVariable("this", "Lcom/android/tools/r8/debug/LocalEndTest;", null, l0, l1, 0);
      mv.visitMaxs(0, 1);
      mv.visitEnd();
    }
    {
      mv = cw.visitMethod(ACC_PUBLIC + ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
      mv.visitCode();
      Label l0 = new Label();
      mv.visitLabel(l0);
      mv.visitLineNumber(25, l0);
      mv.visitTypeInsn(NEW, "com/android/tools/r8/debug/LocalEndTest");
      mv.visitInsn(DUP);
      mv.visitMethodInsn(
          INVOKESPECIAL, "com/android/tools/r8/debug/LocalEndTest", "<init>", "()V", false);
      mv.visitMethodInsn(
          INVOKEVIRTUAL, "com/android/tools/r8/debug/LocalEndTest", "foo", "()V", false);
      Label l1 = new Label();
      mv.visitLabel(l1);
      mv.visitLineNumber(26, l1);
      mv.visitInsn(RETURN);
      Label l2 = new Label();
      mv.visitLabel(l2);
      mv.visitLocalVariable("args", "[Ljava/lang/String;", null, l0, l2, 0);
      mv.visitMaxs(2, 1);
      mv.visitEnd();
    }
    cw.visitEnd();

    return cw.toByteArray();
  }
}
