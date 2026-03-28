package io.github.umutcansu.traceflow.plugin.asm

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.AdviceAdapter

private const val NOT_TRACE_DESC  = "Lio/github/umutcansu/traceflow/NotTrace;"
private const val TRACE_LOG_OWNER = "io/github/umutcansu/traceflow/TraceLog"

class TraceMethodVisitor(
  api: Int,
  mv: MethodVisitor,
  access: Int,
  private val methodName: String,
  descriptor: String,
  private val className: String,
  private val sourceFile: String,
  private val config: TraceConfig,
) : AdviceAdapter(api, mv, access, methodName, descriptor) {

  private var methodNotTrace = false
  private var startTimeLocal = -1
  private var entryLine = 0
  private var currentLine = 0

  // try-catch map: handler label -> try start line
  private val catchHandlerLines = mutableMapOf<Label, Int>()
  private var pendingTryStartLine = 0

  // Parameter info (descriptor = AdviceAdapter's super constructor param -> methodDesc field)
  private val argTypes: Array<Type> = Type.getArgumentTypes(descriptor)
  private val isStatic = (access and Opcodes.ACC_STATIC) != 0
  private val desc: String = descriptor

  // -- Annotation check -------------------------------------------------------

  override fun visitAnnotation(annDescriptor: String, visible: Boolean): AnnotationVisitor? {
    if (annDescriptor == NOT_TRACE_DESC) methodNotTrace = true
    return super.visitAnnotation(annDescriptor, visible)
  }

  // -- Line number tracking ----------------------------------------------------

  override fun visitLineNumber(line: Int, start: Label) {
    currentLine = line
    if (entryLine == 0) entryLine = line
    super.visitLineNumber(line, start)
  }

  // -- Try/catch table registration --------------------------------------------

  override fun visitTryCatchBlock(start: Label, end: Label, handler: Label, type: String?) {
    super.visitTryCatchBlock(start, end, handler, type)
    if (config.logTryCatch) {
      // Mark handler label -> line where try started (currentLine may not be exact yet)
      catchHandlerLines[handler] = pendingTryStartLine
    }
  }

  // -- Label visit: catch handler detection ------------------------------------

  override fun visitLabel(label: Label) {
    super.visitLabel(label)
    if (config.logTryCatch && catchHandlerLines.containsKey(label)) {
      val tryStart = catchHandlerLines[label] ?: 0
      // At catch handler entry, exception is on the stack -- dup and store to local
      mv.visitInsn(Opcodes.DUP)
      val exLocal = newLocal(Type.getType(Throwable::class.java))
      mv.visitVarInsn(Opcodes.ASTORE, exLocal)
      // Push in correct order: caught(cn, mn, file, catchLine, tryStartLine, throwable)
      pushRuntimeClassName()
      mv.visitLdcInsn(methodName)
      mv.visitLdcInsn(sourceFile)
      mv.visitIntInsn(Opcodes.SIPUSH, currentLine)
      mv.visitIntInsn(Opcodes.SIPUSH, tryStart)
      mv.visitVarInsn(Opcodes.ALOAD, exLocal)
      mv.visitMethodInsn(
        Opcodes.INVOKESTATIC, TRACE_LOG_OWNER, "caught",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IILjava/lang/Throwable;)V", false,
      )
    }
  }

  // -- Method entry injection --------------------------------------------------

  override fun onMethodEnter() {
    if (methodNotTrace) return

    // Record start time (for duration calculation at exit)
    if (config.logDuration) {
      startTimeLocal = newLocal(Type.LONG_TYPE)
      mv.visitMethodInsn(
        Opcodes.INVOKESTATIC, "java/lang/System",
        "currentTimeMillis", "()J", false,
      )
      mv.visitVarInsn(Opcodes.LSTORE, startTimeLocal)
    }

    // Entry log
    if (config.logParams && argTypes.isNotEmpty()) {
      // Inject parameter names and values
      pushParamArrays()
      // stack: [nameArray, valueArray]
      // pushEnterWithParams stores them to locals, then pushes cn, mn, file, line, names, values in order
      pushEnterWithParams()
    } else {
      pushRuntimeClassName()
      mv.visitLdcInsn(methodName)
      mv.visitLdcInsn(sourceFile)
      mv.visitIntInsn(Opcodes.SIPUSH, entryLine)
      mv.visitMethodInsn(
        Opcodes.INVOKESTATIC, TRACE_LOG_OWNER, "enter",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V", false,
      )
    }
  }

  // -- Method exit injection ---------------------------------------------------

  override fun onMethodExit(opcode: Int) {
    if (methodNotTrace) return
    if (opcode == Opcodes.ATHROW) return  // exception -> handled on the catch side

    val hasReturn = opcode != Opcodes.RETURN
    val startMs = if (config.logDuration && startTimeLocal >= 0) startTimeLocal else -1

    if (config.logReturnValue && hasReturn) {
      // Dup the return value -> box -> safeToString -> store to local
      val returnType = Type.getReturnType(desc)
      dupReturnValue(returnType)
      boxIfPrimitive(returnType)
      mv.visitMethodInsn(
        Opcodes.INVOKESTATIC, TRACE_LOG_OWNER, "safeToString",
        "(Ljava/lang/Object;)Ljava/lang/String;", false,
      )
      // Store resultStr to local to keep stack order correct
      val resultLocal = newLocal(Type.getType(String::class.java))
      mv.visitVarInsn(Opcodes.ASTORE, resultLocal)
      // Now push in correct order: cn, mn, file, line, startMs, result
      pushRuntimeClassName()
      mv.visitLdcInsn(methodName)
      mv.visitLdcInsn(sourceFile)
      mv.visitIntInsn(Opcodes.SIPUSH, entryLine)
      pushStartTime(startMs)
      mv.visitVarInsn(Opcodes.ALOAD, resultLocal)
      mv.visitMethodInsn(
        Opcodes.INVOKESTATIC, TRACE_LOG_OWNER, "exitWithResult",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IJLjava/lang/Object;)V", false,
      )
    } else {
      pushRuntimeClassName()
      mv.visitLdcInsn(methodName)
      mv.visitLdcInsn(sourceFile)
      mv.visitIntInsn(Opcodes.SIPUSH, entryLine)
      pushStartTime(startMs)
      mv.visitMethodInsn(
        Opcodes.INVOKESTATIC, TRACE_LOG_OWNER, "exit",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IJ)V", false,
      )
    }
  }

  // -- Branch injection --------------------------------------------------------

  override fun visitJumpInsn(opcode: Int, label: Label) {
    if (config.logBranches && !methodNotTrace && isBranchOpcode(opcode)) {
      val line = currentLine
      // Top of stack has the condition value (int 0/!0 or two values)
      // For simple single-operand IFs: DUP -> compare to 0 -> push boolean
      when (opcode) {
        Opcodes.IFEQ, Opcodes.IFNE,
        Opcodes.IFLT, Opcodes.IFGE,
        Opcodes.IFGT, Opcodes.IFLE,
        Opcodes.IFNULL, Opcodes.IFNONNULL -> {
          mv.visitInsn(Opcodes.DUP)
          // DUP'd value -> true if not 0
          val condResult = newLocal(Type.INT_TYPE)
          mv.visitVarInsn(Opcodes.ISTORE, condResult)
          pushRuntimeClassName()
          mv.visitLdcInsn(methodName)
          mv.visitLdcInsn(sourceFile)
          mv.visitIntInsn(Opcodes.SIPUSH, line)
          mv.visitVarInsn(Opcodes.ILOAD, condResult)
          mv.visitMethodInsn(
            Opcodes.INVOKESTATIC, TRACE_LOG_OWNER, "branch",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IZ)V", false,
          )
        }
        else -> { /* IF_ICMP* and IF_ACMP* -- dual operand, skip for now */ }
      }
    }
    super.visitJumpInsn(opcode, label)
  }

  // -- Helpers -----------------------------------------------------------------

  /**
   * For instance methods, pushes this.getClass().getName() -> runtime class name.
   * For static methods, pushes the compile-time class name.
   */
  private val isConstructor = methodName == "init"

  private fun pushRuntimeClassName() {
    if (isStatic || isConstructor) {
      // In constructors, this may not be initialized yet -- use static className
      mv.visitLdcInsn(className)
    } else {
      mv.visitVarInsn(Opcodes.ALOAD, 0) // this
      mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false)
      mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;", false)
    }
  }

  private fun isBranchOpcode(opcode: Int): Boolean = opcode in listOf(
    Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE,
    Opcodes.IFNULL, Opcodes.IFNONNULL,
  )

  private fun pushStartTime(startTimeLocal: Int) {
    if (startTimeLocal >= 0) {
      mv.visitVarInsn(Opcodes.LLOAD, startTimeLocal)
    } else {
      mv.visitLdcInsn(0L)
    }
  }

  private fun dupReturnValue(type: Type) {
    when (type.sort) {
      Type.LONG, Type.DOUBLE -> mv.visitInsn(Opcodes.DUP2)
      else -> mv.visitInsn(Opcodes.DUP)
    }
  }

  private fun boxIfPrimitive(type: Type) {
    when (type.sort) {
      Type.INT     -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false)
      Type.LONG    -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long",    "valueOf", "(J)Ljava/lang/Long;",    false)
      Type.BOOLEAN -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false)
      Type.FLOAT   -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float",   "valueOf", "(F)Ljava/lang/Float;",   false)
      Type.DOUBLE  -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double",  "valueOf", "(D)Ljava/lang/Double;",  false)
      Type.BYTE    -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Byte",    "valueOf", "(B)Ljava/lang/Byte;",    false)
      Type.SHORT   -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Short",   "valueOf", "(S)Ljava/lang/Short;",   false)
      Type.CHAR    -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Character","valueOf","(C)Ljava/lang/Character;",false)
      // OBJECT, ARRAY -> already a reference
    }
  }

  private fun pushParamArrays() {
    val slotOffset = if (isStatic) 0 else 1  // instance method -> slot 0 = this

    // String[] paramNames
    mv.visitIntInsn(Opcodes.BIPUSH, argTypes.size)
    mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
    var slot = slotOffset
    argTypes.forEachIndexed { i, type ->
      mv.visitInsn(Opcodes.DUP)
      mv.visitIntInsn(Opcodes.BIPUSH, i)
      val paramName = "param$i"  // Source names are unknown without debug info
      val displayName = maskedName(paramName)
      mv.visitLdcInsn(displayName)
      mv.visitInsn(Opcodes.AASTORE)
      slot += type.size
    }
    val namesLocal = newLocal(Type.getType(Array<String>::class.java))
    mv.visitVarInsn(Opcodes.ASTORE, namesLocal)

    // Object[] paramValues
    slot = slotOffset
    mv.visitIntInsn(Opcodes.BIPUSH, argTypes.size)
    mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object")
    argTypes.forEachIndexed { i, type ->
      mv.visitInsn(Opcodes.DUP)
      mv.visitIntInsn(Opcodes.BIPUSH, i)
      loadArg(slot, type)
      boxIfPrimitive(type)
      mv.visitInsn(Opcodes.AASTORE)
      slot += type.size
    }
    val valuesLocal = newLocal(Type.getType(Array<Any>::class.java))
    mv.visitVarInsn(Opcodes.ASTORE, valuesLocal)

    mv.visitVarInsn(Opcodes.ALOAD, namesLocal)
    mv.visitVarInsn(Opcodes.ALOAD, valuesLocal)
  }

  private fun maskedName(name: String): String {
    val lower = name.lowercase()
    return if (config.maskParams.any { lower.contains(it.lowercase()) }) "***$name" else name
  }

  private fun loadArg(slot: Int, type: Type) {
    when (type.sort) {
      Type.LONG    -> mv.visitVarInsn(Opcodes.LLOAD, slot)
      Type.DOUBLE  -> mv.visitVarInsn(Opcodes.DLOAD, slot)
      Type.FLOAT   -> mv.visitVarInsn(Opcodes.FLOAD, slot)
      Type.INT, Type.BOOLEAN, Type.BYTE, Type.SHORT, Type.CHAR
                   -> mv.visitVarInsn(Opcodes.ILOAD, slot)
      else         -> mv.visitVarInsn(Opcodes.ALOAD, slot)
    }
  }

  private fun pushEnterWithParams() {
    // Stack currently: [nameArray, valueArray] -- store them to locals
    val valuesLocal = newLocal(Type.getType(Array<Any>::class.java))
    mv.visitVarInsn(Opcodes.ASTORE, valuesLocal)
    val namesLocal = newLocal(Type.getType(Array<String>::class.java))
    mv.visitVarInsn(Opcodes.ASTORE, namesLocal)

    pushRuntimeClassName()
    mv.visitLdcInsn(methodName)
    mv.visitLdcInsn(sourceFile)
    mv.visitIntInsn(Opcodes.SIPUSH, entryLine)
    mv.visitVarInsn(Opcodes.ALOAD, namesLocal)
    mv.visitVarInsn(Opcodes.ALOAD, valuesLocal)
    mv.visitMethodInsn(
      Opcodes.INVOKESTATIC, TRACE_LOG_OWNER, "enterWithParams",
      "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I[Ljava/lang/String;[Ljava/lang/Object;)V",
      false,
    )
  }
}
