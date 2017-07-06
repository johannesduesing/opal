/* BSD 2-Clause License:
 * Copyright (c) 2009 - 2017
 * Software Technology Group
 * Department of Computer Science
 * Technische Universität Darmstadt
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  - Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *  - Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package org.opalj
package tac

import scala.annotation.switch
import org.opalj.collection.immutable.IntSet
import org.opalj.bytecode.BytecodeProcessingFailedException
import org.opalj.br._
import org.opalj.br.analyses.SomeProject
import org.opalj.br.instructions._
import org.opalj.br.Method
import org.opalj.br.ClassHierarchy
import org.opalj.br.cfg.CFG
import org.opalj.ai.BaseAI
import org.opalj.ai.AIResult
import org.opalj.ai.Domain
import org.opalj.ai.domain.RecordDefUse
import org.opalj.ai.domain.l1.DefaultDomainWithCFGAndDefUse

/**
 * Factory to convert the bytecode of a method into a three address representation using the
 * results of a(n) (local) abstract interpretation of the method.
 *
 * The generated representation is completely parameterized over the domains that were used
 * to perform the abstract interpretation. The only requirement is that the Def/Use information
 * is recorded while performing the abstract interpretation (see
 * [[org.opalj.ai.domain.RecordDefUse]]). The generated representation is necessarily in
 * static single assignment form: each variable is assigned exactly once, and every variable is
 * defined before it is used. However, no PHI instructions are inserted; instead - in case of a
 * use - we simply directly refer to all usage sites.
 *
 * @author Michael Eichberg
 */
object TACAI {

    /**
     * Returns a map which maps an ai-based value origin for a parameter to the tac value origin;
     * to lookup the tac based origin, the ai-based origin aiVO has to be negated and 1 has to
     * be subtracted.
     *
     * @return An implicit map (keys are aiVOKey = `-aiVo-1`) from `aiVO` to `tacVo`.
     */
    def normalizeParameterOriginsMap(
        descriptor: MethodDescriptor,
        isStatic:   Boolean
    ): Array[Int] = {
        val parameterTypes = descriptor.parameterTypes
        val parametersCount = descriptor.parametersCount

        // we need this `map` only temporarily; and this is always large enough...
        val aiVOToTACVo = new Array[Int](parametersCount * 2 + 1)
        var aiVO = -1 // initialized for the static method case
        var tacVO = -2 // initialized for the static method case

        if (!isStatic) {
            aiVOToTACVo(0) = -1 // basically vo -1 is mapped to tacVO -1
            aiVO = -2
        }

        var i = 0 // initialized for the static method case
        while (i < parametersCount) {
            aiVOToTACVo(-aiVO - 1) = tacVO
            tacVO -= 1
            aiVO -= parameterTypes(0).computationalType.operandSize
            i += 1
        }

        aiVOToTACVo
    }

    private[this] final val NoParameters = new Parameters(new Array[DUVar[_]](0))

    def apply(
        project: SomeProject,
        method:  Method
    )(
        domain: Domain with RecordDefUse = new DefaultDomainWithCFGAndDefUse(project, method)
    ): TACode[TACMethodParameter, DUVar[domain.DomainValue]] = {
        val aiResult = BaseAI(project.classFile(method), method, domain)
        TACAI(method, project.classHierarchy, aiResult)(Nil)
    }

    /**
     * Converts the bytecode of a method into a three address representation using
     * the result of an abstract interpretation.
     *
     * @param   method A method with a body. I.e., a non-native, non-abstract method.
     * @param   aiResult The result of the abstract interpretation of the respective method.
     * @param   optimizations The transformations that should be executed (NoOptimizations
     *          is always possible).
     * @return  The array with the generated statements.
     */
    def apply(
        method:         Method,
        classHierarchy: ClassHierarchy,
        aiResult:       AIResult { val domain: Domain with RecordDefUse }
    )(
        optimizations: List[TACOptimization[TACMethodParameter, DUVar[aiResult.domain.DomainValue]]]
    ): TACode[TACMethodParameter, DUVar[aiResult.domain.DomainValue]] = {

        import BinaryArithmeticOperators._
        import RelationalOperators._
        import UnaryArithmeticOperators._

        val code = method.body.get
        import code.pcOfNextInstruction
        val instructions: Array[Instruction] = code.instructions
        val codeSize: Int = instructions.length
        val domain: aiResult.domain.type = aiResult.domain
        val cfg: CFG = domain.bbCFG
        def wasExecuted(pc: PC) = cfg.bb(pc) != null
        val operandsArray: aiResult.domain.OperandsArray = aiResult.operandsArray
        val localsArray: aiResult.domain.LocalsArray = aiResult.localsArray

        // We already have the def-use information directly available, hence, for
        // instructions such as swap and dup, which do not create "relevant"
        // uses, we do not have to create multiple instructions, therefore, we
        // can directly create the "final list" of statements (which will include nops
        // for all useless instructions).
        val statements = new Array[Stmt[DUVar[aiResult.domain.DomainValue]]](codeSize)
        val pcToIndex = new Array[Int](codeSize + 1 /* +1 if the try includes the last inst. */ )

        // The map which we use to map an ai based value origin (vo) for a parameter to a tac origin.
        // To get the target value the ai based vo has to be negated and we have to add -1.
        // E.g., if the ai-based vo is -1 then the index which needs to be used is -(-1)-1 ==> 0
        // which will contain (for -1 only) the value -1.
        val aiVOToTACVo: Array[Int] = normalizeParameterOriginsMap(method.descriptor, method.isStatic)
        def normalizeParameterOrigins(aiVOs: IntSet): IntSet = {
            if (aiVOs eq null) {
                IntSet.empty
            } else {
                aiVOs.map { aiVO ⇒ if (aiVO < 0) aiVOToTACVo(-aiVO - 1) else aiVO }
            }
        }

        var pc: PC = 0
        var index: Int = 0

        do {
            val nextPC = pcOfNextInstruction(pc)
            val instruction = instructions(pc)
            val opcode = instruction.opcode

            def addStmt(stmt: Stmt[DUVar[aiResult.domain.DomainValue]]): Unit = {
                if (cfg.bb(pc).startPC != pc && statements(index - 1).astID == Nop.ASTID) {
                    // ... we are not at the beginning of a basic block, but the previous
                    // instruction was a NOP instruction... let's replace it by this instruction.
                    statements(index - 1) = stmt
                    pcToIndex(pc) = index - 1
                } else {
                    statements(index) = stmt
                    pcToIndex(pc) = index
                    index += 1
                }
            }

            def addNOP(): Unit = {
                // We only add a NOP if it is the first instruction of a basic block since
                // we want to ensure that we don't have to rewrite the CFG during the initial
                // transformation
                if (cfg.bb(pc).startPC == pc) {
                    statements(index) = Nop(pc)
                    pcToIndex(pc) = index
                    index += 1
                } else {
                    pcToIndex(pc) = index - 1
                }
            }

            def operandUse(index: Int): UVar[aiResult.domain.DomainValue] = {
                val operands = operandsArray(pc)
                // get the definition site; recall: negative pcs refer to parameters
                val defSites = normalizeParameterOrigins(domain.operandOrigin(pc, index))
                UVar(aiResult.domain)(operands(index), defSites)
            }

            def registerUse(index: Int): UVar[aiResult.domain.DomainValue] = {
                val locals = localsArray(pc)
                // get the definition site; recall: negative pcs refer to parameters
                val defSites = normalizeParameterOrigins(domain.localOrigin(pc, index))
                UVar(aiResult.domain)(locals(index), defSites)
            }

            /**
             * Creates a local variable using the current pc and the type
             * information from the domain value.
             */
            def addInitLocalValStmt(
                pc:   PC,
                v:    aiResult.domain.DomainValue,
                expr: Expr[DUVar[aiResult.domain.DomainValue]]
            ): Unit = {
                val uses = normalizeParameterOrigins(domain.usedBy(pc))
                if (uses ne null) {
                    val localVal = DVar(aiResult.domain)(pc, v, uses)
                    addStmt(Assignment(pc, localVal, expr))
                } else if (expr.isSideEffectFree) {
                    addNOP()
                } else {
                    addStmt(ExprStmt(pc, expr))
                }
            }

            def arrayLoad(): Unit = {
                val index = operandUse(0)
                val arrayRef = operandUse(1)
                // to get the precise type we take a look at the next instruction's
                // top operand value
                val source = ArrayLoad(pc, index, arrayRef)
                if (wasExecuted(nextPC)) {
                    addInitLocalValStmt(pc, operandsArray(nextPC).head, source)
                } else {
                    addStmt(FailingExpression(pc, source))
                }
            }

            def binaryArithmeticOperation(operator: BinaryArithmeticOperator): Unit = {
                val value2 = operandUse(0)
                val value1 = operandUse(1)
                val cTpe = operandsArray(nextPC).head.computationalType
                val binExpr = BinaryExpr(pc, cTpe, operator, value1, value2)
                // may fail in case of a div by zero...
                if (wasExecuted(nextPC)) {
                    addInitLocalValStmt(pc, operandsArray(nextPC).head, binExpr)
                } else {
                    addStmt(FailingExpression(pc, binExpr))
                }
            }

            def prefixArithmeticOperation(operator: UnaryArithmeticOperator): Unit = {
                val value = operandUse(0)
                val cTpe = operandsArray(nextPC).head.computationalType
                val preExpr = PrefixExpr(pc, cTpe, operator, value)
                addInitLocalValStmt(pc, operandsArray(nextPC).head, preExpr)
            }

            def primitiveCastOperation(targetTpe: BaseType): Unit = {
                val value = operandUse(0)
                val castExpr = PrimitiveTypecastExpr(pc, targetTpe, value)
                addInitLocalValStmt(pc, operandsArray(nextPC).head, castExpr)
            }

            def newArray(arrayType: ArrayType): Unit = {
                val count = operandUse(0)
                val newArray = NewArray(pc, List(count), arrayType)
                addInitLocalValStmt(pc, operandsArray(nextPC).head, newArray)
            }

            def loadConstant(instr: LoadConstantInstruction[_]): Unit = {
                instr match {
                    case LDCInt(value) ⇒
                        addInitLocalValStmt(pc, operandsArray(nextPC).head, IntConst(pc, value))

                    case LDCFloat(value) ⇒
                        addInitLocalValStmt(pc, operandsArray(nextPC).head, FloatConst(pc, value))

                    case LDCClass(value) ⇒
                        addInitLocalValStmt(pc, operandsArray(nextPC).head, ClassConst(pc, value))

                    case LDCString(value) ⇒
                        addInitLocalValStmt(pc, operandsArray(nextPC).head, StringConst(pc, value))

                    case LDCMethodHandle(value) ⇒
                        val lVal = operandsArray(nextPC).head
                        addInitLocalValStmt(pc, lVal, MethodHandleConst(pc, value))

                    case LDCMethodType(value) ⇒
                        val lVal = operandsArray(nextPC).head
                        addInitLocalValStmt(pc, lVal, MethodTypeConst(pc, value))

                    case LoadDouble(value) ⇒
                        addInitLocalValStmt(pc, operandsArray(nextPC).head, DoubleConst(pc, value))

                    case LoadLong(value) ⇒
                        addInitLocalValStmt(pc, operandsArray(nextPC).head, LongConst(pc, value))

                    case _ ⇒
                        val message = s"unexpected constant $instr"
                        throw BytecodeProcessingFailedException(message)
                }
            }

            def compareValues(op: RelationalOperator): Unit = {
                val value2 = operandUse(0)
                val value1 = operandUse(1)
                val compare = Compare(pc, value1, op, value2)
                addInitLocalValStmt(pc, operandsArray(nextPC).head, compare)
            }

            def as[T <: Instruction](i: Instruction): T = i.asInstanceOf[T]

            (opcode: @switch) match {
                case ALOAD_0.opcode | ALOAD_1.opcode | ALOAD_2.opcode | ALOAD_3.opcode |
                    ALOAD.opcode |
                    ASTORE_0.opcode | ASTORE_1.opcode | ASTORE_2.opcode | ASTORE_3.opcode |
                    ASTORE.opcode |
                    ILOAD_0.opcode | ILOAD_1.opcode | ILOAD_2.opcode | ILOAD_3.opcode |
                    ILOAD.opcode |
                    ISTORE_0.opcode | ISTORE_1.opcode | ISTORE_2.opcode | ISTORE_3.opcode |
                    ISTORE.opcode |
                    DLOAD_0.opcode | DLOAD_1.opcode | DLOAD_2.opcode | DLOAD_3.opcode |
                    DLOAD.opcode |
                    DSTORE_0.opcode | DSTORE_1.opcode | DSTORE_2.opcode | DSTORE_3.opcode |
                    DSTORE.opcode |
                    FLOAD_0.opcode | FLOAD_1.opcode | FLOAD_2.opcode | FLOAD_3.opcode |
                    FLOAD.opcode |
                    FSTORE_0.opcode | FSTORE_1.opcode | FSTORE_2.opcode | FSTORE_3.opcode |
                    FSTORE.opcode |
                    LLOAD_0.opcode | LLOAD_1.opcode | LLOAD_2.opcode | LLOAD_3.opcode |
                    LLOAD.opcode |
                    LSTORE_0.opcode | LSTORE_1.opcode | LSTORE_2.opcode | LSTORE_3.opcode |
                    LSTORE.opcode ⇒
                    addNOP()

                case IRETURN.opcode | LRETURN.opcode | FRETURN.opcode | DRETURN.opcode |
                    ARETURN.opcode ⇒
                    addStmt(ReturnValue(pc, operandUse(0)))

                case RETURN.opcode ⇒ addStmt(Return(pc))

                case AALOAD.opcode |
                    DALOAD.opcode | FALOAD.opcode | LALOAD.opcode |
                    IALOAD.opcode | SALOAD.opcode | CALOAD.opcode |
                    BALOAD.opcode ⇒ arrayLoad()

                case AASTORE.opcode | DASTORE.opcode |
                    FASTORE.opcode | IASTORE.opcode |
                    LASTORE.opcode | SASTORE.opcode |
                    BASTORE.opcode | CASTORE.opcode ⇒
                    val operandVar = operandUse(0)
                    val index = operandUse(1)
                    val arrayRef = operandUse(2)
                    addStmt(ArrayStore(pc, arrayRef, index, operandVar))

                case ARRAYLENGTH.opcode ⇒
                    val arrayRef = operandUse(0)
                    val lengthExpr = ArrayLength(pc, arrayRef)
                    if (wasExecuted(nextPC)) {
                        addInitLocalValStmt(pc, operandsArray(nextPC).head, lengthExpr)
                    } else {
                        // IMPROVE Encode information about the failing exception!
                        addStmt(FailingExpression(pc, lengthExpr))
                    }

                case BIPUSH.opcode | SIPUSH.opcode ⇒
                    val value = as[LoadConstantInstruction[Int]](instruction).value
                    addInitLocalValStmt(pc, operandsArray(nextPC).head, IntConst(pc, value))

                case IF_ICMPEQ.opcode | IF_ICMPNE.opcode |
                    IF_ICMPLT.opcode | IF_ICMPLE.opcode |
                    IF_ICMPGT.opcode | IF_ICMPGE.opcode ⇒
                    // TODO Check if this if is actually useless... i.e., either a GOTO or a NOP
                    //if(cfg.bb(pc).endPC != pc) {
                    //    // The comparison is actually
                    //    addNOP()
                    //    // ... and correct def-use information...
                    //}
                    val ifInstr = as[IFICMPInstruction](instruction)
                    val value2 = operandUse(0)
                    val value1 = operandUse(1)
                    val targetPC = pc + ifInstr.branchoffset
                    addStmt(If(pc, value1, ifInstr.condition, value2, targetPC))

                case IFEQ.opcode | IFNE.opcode |
                    IFLT.opcode | IFLE.opcode |
                    IFGT.opcode | IFGE.opcode ⇒
                    val IF0Instruction(condition, branchoffset) = instruction
                    val value = operandUse(0)
                    // let's calculate the final address
                    val targetPC = pc + branchoffset
                    val cmpVal = IntConst(ai.ValueOriginForVMLevelValue(pc), 0)
                    addStmt(If(pc, value, condition, cmpVal, targetPC))

                case IF_ACMPEQ.opcode | IF_ACMPNE.opcode ⇒
                    val IFACMPInstruction(condition, branchoffset) = instruction
                    val value2 = operandUse(0)
                    val value1 = operandUse(1)
                    // let's calculate the final address
                    val targetPC = pc + branchoffset
                    addStmt(If(pc, value1, condition, value2, targetPC))

                case IFNONNULL.opcode | IFNULL.opcode ⇒
                    val IFXNullInstruction(condition, branchoffset) = instruction
                    val value = operandUse(0)
                    val targetPC = pc + branchoffset
                    val cmpVal = NullExpr(ai.ValueOriginForVMLevelValue(pc))
                    addStmt(If(pc, value, condition, cmpVal, targetPC))

                case DCMPG.opcode | FCMPG.opcode ⇒ compareValues(CMPG)
                case DCMPL.opcode | FCMPL.opcode ⇒ compareValues(CMPL)
                case LCMP.opcode                 ⇒ compareValues(CMP)

                case SWAP.opcode                 ⇒ addNOP()

                case DADD.opcode | FADD.opcode | IADD.opcode | LADD.opcode ⇒
                    binaryArithmeticOperation(Add)
                case DDIV.opcode | FDIV.opcode | IDIV.opcode | LDIV.opcode ⇒
                    binaryArithmeticOperation(Divide)
                case DMUL.opcode | FMUL.opcode | IMUL.opcode | LMUL.opcode ⇒
                    binaryArithmeticOperation(Multiply)
                case DREM.opcode | FREM.opcode | IREM.opcode | LREM.opcode ⇒
                    binaryArithmeticOperation(Modulo)
                case DSUB.opcode | FSUB.opcode | ISUB.opcode | LSUB.opcode ⇒
                    binaryArithmeticOperation(Subtract)

                case DNEG.opcode | FNEG.opcode | INEG.opcode | LNEG.opcode ⇒
                    prefixArithmeticOperation(Negate)

                case IINC.opcode ⇒
                    val IINC(index, const) = instruction
                    val indexReg = registerUse(index)
                    val incVal = IntConst(pc, const)
                    val iinc = BinaryExpr(pc, ComputationalTypeInt, Add, indexReg, incVal)
                    addInitLocalValStmt(pc, localsArray(nextPC)(index), iinc)

                case IAND.opcode | LAND.opcode   ⇒ binaryArithmeticOperation(And)
                case IOR.opcode | LOR.opcode     ⇒ binaryArithmeticOperation(Or)
                case ISHL.opcode | LSHL.opcode   ⇒ binaryArithmeticOperation(ShiftLeft)
                case ISHR.opcode | LSHR.opcode   ⇒ binaryArithmeticOperation(ShiftRight)
                case IUSHR.opcode | LUSHR.opcode ⇒ binaryArithmeticOperation(UnsignedShiftRight)
                case IXOR.opcode | LXOR.opcode   ⇒ binaryArithmeticOperation(XOr)

                case ICONST_0.opcode | ICONST_1.opcode |
                    ICONST_2.opcode | ICONST_3.opcode |
                    ICONST_4.opcode | ICONST_5.opcode |
                    ICONST_M1.opcode ⇒
                    val IConstInstruction(value) = instruction
                    addInitLocalValStmt(pc, operandsArray(nextPC).head, IntConst(pc, value))

                case ACONST_NULL.opcode ⇒
                    addInitLocalValStmt(pc, operandsArray(nextPC).head, NullExpr(pc))

                case DCONST_0.opcode | DCONST_1.opcode ⇒
                    val value = as[LoadConstantInstruction[Double]](instruction).value
                    addInitLocalValStmt(pc, operandsArray(nextPC).head, DoubleConst(pc, value))

                case FCONST_0.opcode | FCONST_1.opcode | FCONST_2.opcode ⇒
                    val value = as[LoadConstantInstruction[Float]](instruction).value
                    addInitLocalValStmt(pc, operandsArray(nextPC).head, FloatConst(pc, value))

                case LCONST_0.opcode | LCONST_1.opcode ⇒
                    val value = as[LoadConstantInstruction[Long]](instruction).value
                    addInitLocalValStmt(pc, operandsArray(nextPC).head, LongConst(pc, value))

                case LDC.opcode | LDC_W.opcode | LDC2_W.opcode ⇒
                    loadConstant(as[LoadConstantInstruction[_]](instruction))

                case INVOKEINTERFACE.opcode | INVOKESPECIAL.opcode | INVOKEVIRTUAL.opcode ⇒
                    val call @ MethodInvocationInstruction(
                        declClass, isInterface,
                        name, descriptor) = instruction
                    val parametersCount = descriptor.parametersCount
                    val params = (0 until parametersCount).map(i ⇒ operandUse(i))(Seq.canBuildFrom)
                    val receiver = operandUse(parametersCount) // this is the self reference
                    val returnType = descriptor.returnType
                    if (returnType.isVoidType) {
                        if (call.isVirtualMethodCall)
                            addStmt(VirtualMethodCall(
                                pc,
                                declClass, isInterface, name, descriptor,
                                receiver,
                                params
                            ))
                        else
                            addStmt(NonVirtualMethodCall(
                                pc,
                                declClass, isInterface, name, descriptor,
                                receiver,
                                params
                            ))
                    } else {
                        val expr =
                            if (call.isVirtualMethodCall)
                                VirtualFunctionCall(
                                    pc,
                                    declClass, isInterface, name, descriptor,
                                    receiver,
                                    params
                                )
                            else
                                NonVirtualFunctionCall(
                                    pc,
                                    declClass, isInterface, name, descriptor,
                                    receiver,
                                    params
                                )
                        if (wasExecuted(nextPC)) {
                            addInitLocalValStmt(pc, operandsArray(nextPC).head, expr)
                        } else {
                            // IMPROVE Encode information about the failing exception!
                            addStmt(FailingExpression(pc, expr))
                        }
                    }

                case INVOKESTATIC.opcode ⇒
                    val INVOKESTATIC(declaringClass, isInterface, name, descriptor) = instruction
                    val parametersCount = descriptor.parametersCount
                    val params = (0 until parametersCount).map(i ⇒ operandUse(i))(Seq.canBuildFrom)
                    val returnType = descriptor.returnType
                    if (returnType.isVoidType) {
                        val staticCall =
                            StaticMethodCall(
                                pc,
                                declaringClass, isInterface, name, descriptor,
                                params
                            )
                        addStmt(staticCall)
                    } else {
                        val expr =
                            StaticFunctionCall(
                                pc,
                                declaringClass, isInterface, name, descriptor,
                                params
                            )
                        if (wasExecuted(nextPC)) {
                            addInitLocalValStmt(pc, operandsArray(nextPC).head, expr)
                        } else {
                            // IMPROVE Encode information about the failing exception!
                            addStmt(FailingExpression(pc, expr))
                        }
                    }

                case INVOKEDYNAMIC.opcode ⇒
                    val INVOKEDYNAMIC(bootstrapMethod, name, methodDescriptor) = instruction
                    val parametersCount = methodDescriptor.parametersCount
                    val params = (0 until parametersCount).map(i ⇒ operandUse(i))(Seq.canBuildFrom)
                    val expr = Invokedynamic(pc, bootstrapMethod, name, methodDescriptor, params)
                    if (wasExecuted(nextPC)) {
                        addInitLocalValStmt(pc, operandsArray(nextPC).head, expr)
                    } else {
                        // IMPROVE Encode information about the failing exception!
                        addStmt(FailingExpression(pc, expr))
                    }

                case PUTSTATIC.opcode ⇒
                    val PUTSTATIC(declaringClass, name, fieldType) = instruction
                    val value = operandUse(0)
                    val putStatic = PutStatic(pc, declaringClass, name, fieldType, value)
                    addStmt(putStatic)

                case PUTFIELD.opcode ⇒
                    val PUTFIELD(declaringClass, name, fieldType) = instruction
                    val value = operandUse(0)
                    val objRef = operandUse(1)
                    val putField = PutField(pc, declaringClass, name, fieldType, objRef, value)
                    if (wasExecuted(nextPC)) {
                        addStmt(putField)
                    } else {
                        // IMPROVE Encode information about the failing exception!
                        addStmt(FailingStatement(pc, putField))
                    }

                case GETSTATIC.opcode ⇒
                    val GETSTATIC(declaringClass, name, fieldType) = instruction
                    val getStatic = GetStatic(pc, declaringClass, name, fieldType)
                    addInitLocalValStmt(pc, operandsArray(nextPC).head, getStatic)

                case GETFIELD.opcode ⇒
                    val GETFIELD(declaringClass, name, fieldType) = instruction
                    val getField = GetField(pc, declaringClass, name, fieldType, operandUse(0))
                    if (wasExecuted(nextPC)) {
                        addInitLocalValStmt(pc, operandsArray(nextPC).head, getField)
                    } else {
                        // IMPROVE Encode information about the failing exception!
                        addStmt(FailingExpression(pc, getField))
                    }

                case NEW.opcode ⇒
                    val NEW(objectType) = instruction
                    val newObject = New(pc, objectType)
                    if (wasExecuted(nextPC)) {
                        addInitLocalValStmt(pc, operandsArray(nextPC).head, newObject)
                    } else {
                        // IMPROVE Encode information about the failing exception!
                        addStmt(FailingExpression(pc, newObject))
                    }

                case NEWARRAY.opcode ⇒
                    newArray(ArrayType(as[NEWARRAY](instruction).elementType))

                case ANEWARRAY.opcode ⇒
                    newArray(ArrayType(as[ANEWARRAY](instruction).componentType))

                case MULTIANEWARRAY.opcode ⇒
                    val MULTIANEWARRAY(arrayType, dimensions) = instruction
                    val counts = (0 until dimensions).map(d ⇒ operandUse(d))(Seq.canBuildFrom)
                    val newArray = NewArray(pc, counts, arrayType)
                    addInitLocalValStmt(pc, operandsArray(nextPC).head, newArray)

                case GOTO.opcode | GOTO_W.opcode ⇒
                    val GotoInstruction(branchoffset) = instruction
                    addStmt(Goto(pc, pc + branchoffset))

                case JSR.opcode | JSR_W.opcode ⇒
                    val JSRInstruction(branchoffset) = instruction
                    addStmt(JumpToSubroutine(pc, pc + branchoffset))
                case RET.opcode ⇒
                    addStmt(Ret(pc, cfg.successors(pc)))

                case NOP.opcode               ⇒ addNOP()
                case POP.opcode | POP2.opcode ⇒ addNOP()

                case INSTANCEOF.opcode ⇒
                    val value1 = operandUse(0)
                    val INSTANCEOF(tpe) = instruction
                    val instanceOf = InstanceOf(pc, value1, tpe)
                    addInitLocalValStmt(pc, operandsArray(nextPC).head, instanceOf)

                case CHECKCAST.opcode ⇒
                    val value1 = operandUse(0)
                    val CHECKCAST(targetType) = instruction
                    val checkcast = Checkcast(pc, value1, targetType)
                    if (wasExecuted(nextPC)) {
                        addInitLocalValStmt(pc, operandsArray(nextPC).head, checkcast)
                    } else {
                        addStmt(FailingExpression(pc, checkcast))
                    }

                case MONITORENTER.opcode ⇒ addStmt(MonitorEnter(pc, operandUse(0)))
                case MONITOREXIT.opcode  ⇒ addStmt(MonitorExit(pc, operandUse(0)))

                case TABLESWITCH.opcode ⇒
                    val index = operandUse(0)
                    val tableSwitch = as[TABLESWITCH](instruction)
                    val defaultTarget = pc + tableSwitch.defaultOffset
                    var caseValue = tableSwitch.low
                    val npairs = tableSwitch.jumpOffsets map { jo ⇒
                        val caseTarget = pc + jo
                        val npair = (caseValue, caseTarget)
                        caseValue += 1
                        npair
                    }
                    addStmt(Switch(pc, defaultTarget, index, npairs))

                case LOOKUPSWITCH.opcode ⇒
                    val index = operandUse(0)
                    val lookupSwitch = as[LOOKUPSWITCH](instruction)
                    val defaultTarget = pc + lookupSwitch.defaultOffset
                    val npairs = lookupSwitch.npairs.map { npair ⇒
                        val (caseValue, branchOffset) = npair
                        val caseTarget = pc + branchOffset
                        (caseValue, caseTarget)
                    }
                    addStmt(Switch(pc, defaultTarget, index, npairs))

                case DUP.opcode | DUP_X1.opcode | DUP_X2.opcode
                    | DUP2.opcode | DUP2_X1.opcode | DUP2_X2.opcode ⇒ addNOP()

                case D2F.opcode | I2F.opcode | L2F.opcode ⇒ primitiveCastOperation(FloatType)
                case D2I.opcode | F2I.opcode | L2I.opcode ⇒ primitiveCastOperation(IntegerType)
                case D2L.opcode | I2L.opcode | F2L.opcode ⇒ primitiveCastOperation(LongType)
                case F2D.opcode | I2D.opcode | L2D.opcode ⇒ primitiveCastOperation(DoubleType)
                case I2C.opcode                           ⇒ primitiveCastOperation(CharType)
                case I2B.opcode                           ⇒ primitiveCastOperation(ByteType)
                case I2S.opcode                           ⇒ primitiveCastOperation(ShortType)

                case ATHROW.opcode                        ⇒ addStmt(Throw(pc, operandUse(0)))

                case WIDE.opcode                          ⇒ addNOP()

                case opcode ⇒
                    throw BytecodeProcessingFailedException(s"unknown opcode: $opcode")
            }

            pc = nextPC
            while (pc < codeSize && !wasExecuted(pc)) {
                pc = pcOfNextInstruction(pc)
            }
        } while (pc < codeSize)

        // add the artificial lastPC + 1 instruction to enable the mapping of exception handlers
        pcToIndex(pc /* == codeSize +1 */ ) = index

        val tacParams: Parameters[TACMethodParameter] = {
            import method.descriptor
            import descriptor.parameterTypes
            if (method.descriptor.parametersCount == 0 && method.isStatic)
                NoParameters.asInstanceOf[Parameters[TACMethodParameter]]
            else {
                val paramCount = method.descriptor.parametersCount + 1
                val paramDVars = new Array[TACMethodParameter](paramCount)

                var usesOrigin = -1
                if (!method.isStatic) {
                    var uses = domain.usedBy(-1)
                    if (uses eq null) { uses = IntSet.empty } else { uses = uses.map(pcToIndex) }
                    paramDVars(0) = TACMethodParameter(-1, uses)
                    usesOrigin = -2
                }
                var pIndex = 1
                while (pIndex < paramCount) {
                    var uses = domain.usedBy(usesOrigin)
                    // the uses for parameters never refer to parameters => have negative values!
                    if (uses eq null) { uses = IntSet.empty } else { uses = uses.map(pcToIndex) }
                    paramDVars(pIndex) = TACMethodParameter(-pIndex - 1, uses)
                    usesOrigin -= parameterTypes(pIndex - 1).operandSize
                    pIndex += 1
                }
                new Parameters(paramDVars)
            }
        }

        val tacStmts = {
            val tacStmts = new Array[Stmt[DUVar[aiResult.domain.DomainValue]]](index)
            var s = 0
            while (s < index) {
                val stmt = statements(s)
                stmt.remapIndexes(pcToIndex)
                tacStmts(s) = stmt
                s += 1
            }
            tacStmts
        }
        val taCodeCFG = cfg.mapPCsToIndexes(pcToIndex, lastIndex = index - 1)
        val taExceptionHanders = updateExceptionHandlers(code.exceptionHandlers, pcToIndex)
        val lnt = code.lineNumberTable
        val taCode = TACode(tacParams, tacStmts, taCodeCFG, taExceptionHanders, lnt)

        if (optimizations.nonEmpty) {
            val base = TACOptimizationResult(taCode, wasTransformed = false)
            val result = optimizations.foldLeft(base)((tac, optimization) ⇒ optimization(tac))
            result.code
        } else {
            taCode
        }
    }

}
