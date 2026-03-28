package io.github.umutcansu.traceflow.plugin.asm

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

private const val NOT_TRACE_DESC = "Lio/github/umutcansu/traceflow/NotTrace;"

class TraceClassVisitor(
  api: Int,
  next: ClassVisitor,
  private val className: String,
  private val config: TraceConfig,
) : ClassVisitor(api, next) {

  // Bytecode class name (e.g. "AuthRepositoryImpl" or "LoginViewModel$login$1")
  private val simpleClassName = className.substringAfterLast('/')

  // Clean class name for log display
  // LoginViewModel$login$1  -> LoginViewModel.login
  // LoginViewModel$Companion -> LoginViewModel.Companion
  // Foo$$Lambda$1            -> Foo
  private val displayClassName: String = simpleClassName
    .replace(Regex("""\$\$[^$]*"""), "")   // $$Lambda$1, $$sam$0 -> remove
    .replace(Regex("""\$\d+"""), "")        // $1, $2 -> remove
    .replace('$', '.')                       // $ -> .
    .replace(Regex("""\\.+"""), ".")         // consecutive dots -> single dot
    .trim('.')
    .ifEmpty { simpleClassName }             // if completely empty, use original

  // Source file name -- comes from visitSource
  private var sourceFile: String = "$simpleClassName.kt"

  // Class-level @NotTrace present?
  private var classNotTrace = false

  override fun visitSource(source: String?, debug: String?) {
    if (source != null) sourceFile = source
    super.visitSource(source, debug)
  }

  override fun visitAnnotation(descriptor: String, visible: Boolean): org.objectweb.asm.AnnotationVisitor? {
    if (descriptor == NOT_TRACE_DESC) classNotTrace = true
    return super.visitAnnotation(descriptor, visible)
  }

  override fun visitMethod(
    access: Int,
    name: String,
    descriptor: String,
    signature: String?,
    exceptions: Array<out String>?,
  ): MethodVisitor {
    val mv = super.visitMethod(access, name, descriptor, signature, exceptions)

    // -- Filters ----------------------------------------------------------------

    // Class-level @NotTrace -> skip all methods
    if (classNotTrace) return mv

    // Static init and lambda bridge ($$ containing) -> skip
    if (name == "<clinit>" || name.contains("\$\$")) return mv

    // Kotlin synthetic private accessor method (access$xxx) -> skip
    if (name.startsWith("access\$")) return mv

    // @JvmOverloads default stub ($default suffix) -> skip
    if (name.endsWith("\$default")) return mv

    // Suspend state machine -> skip
    if (name == "invokeSuspend" &&
      descriptor.endsWith("Ljava/lang/Object;") &&
      descriptor.contains("Lkotlin/coroutines/Continuation;")) return mv

    val isSynthetic = (access and Opcodes.ACC_SYNTHETIC) != 0

    // Kotlin property getter/setter -> skip (verbose, no business logic)
    val isGetterOrSetter = name.length > 3 &&
      (name.startsWith("get") || name.startsWith("set")) &&
      name[3].isUpperCase()
    if (isGetterOrSetter && !isSynthetic) return mv

    // -- Display name conversion ------------------------------------------------

    val displayMethod = when {
      // Constructor -> "init"
      name == "<init>" -> "init"

      // getUsername -> username  |  setUsername -> username=
      name.startsWith("get") && name.length > 3 && name[3].isUpperCase() ->
        name[3].lowercaseChar() + name.substring(4)
      name.startsWith("set") && name.length > 3 && name[3].isUpperCase() ->
        name[3].lowercaseChar() + name.substring(4) + "="

      // invokeSuspend (passed filter, e.g. different descriptor) -> {coroutine}
      name == "invokeSuspend" -> "{coroutine}"

      else -> name
    }

    return TraceMethodVisitor(
      api        = api,
      mv         = mv,
      access     = access,
      methodName = displayMethod,
      descriptor = descriptor,
      className  = displayClassName,
      sourceFile = sourceFile,
      config     = config,
    )
  }
}
