/* BSD 2-Clause License:
 * Copyright (c) 2009 - 2014
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

import scala.language.existentials
import org.opalj.log.GlobalLogContext
import org.opalj.log.OPALLogger
import org.opalj.br.Method
import org.opalj.br.MethodDescriptor
import org.opalj.br.Code
import org.opalj.br.instructions.Instruction
import org.opalj.collection.immutable.FastList

/**
 * Implementation of an abstract interpretation (ai) framework – also referred to as OPAL.
 *
 * Please note, that OPAL/the abstract interpreter just refers to the classes and traits
 * defined in this package (`ai`). The classes and traits defined in the sub-packages
 * (in particular in `domain`) are not considered to be part of the core of OPAL/the
 * abstract interpreter.
 *
 * @note This framework assumes that the analyzed bytecode is valid; i.e., the JVM's
 *      bytecode verifier would be able to verify the code. Furthermore, load-time errors
 *      (e.g., `LinkageErrors`) are – by default – completely ignored to facilitate the
 *      analysis of parts of a project. In general, if the presented bytecode is not valid,
 *      the result is undefined (i.e., OPAL may report meaningless results, crash or run
 *      indefinitely).
 *
 * @see [[org.opalj.ai.AI]] - Implements the abstract interpreter that
 *      processes a methods code and uses an analysis-specific domain to perform the
 *      abstract computations.
 * @see [[org.opalj.ai.Domain]] - The core interface between the abstract
 *      interpretation framework and the abstract domain that is responsible for
 *      performing the abstract computations.
 *
 * @author Michael Eichberg
 */
package object ai {

    final val FrameworkName = "Abstract Interpretation Framework"

    {
        implicit val logContext = GlobalLogContext
        try {
            scala.Predef.assert(false) // <= tests whether assertions are on or off...
            OPALLogger.info("OPAL", s"$FrameworkName - Production Build")
        } catch {
            case _: AssertionError ⇒
                val message = s"$FrameworkName - Development Build (Assertions are enabled)"
                OPALLogger.info("OPAL", message)
        }
    }

    /**
     * Type alias that can be used if the AI can use all kinds of domains.
     *
     * @note This type alias serves comprehension purposes only.
     */
    type SomeAI[D <: Domain] = AI[_ >: D]

    type PrimitiveValuesFactory = IntegerValuesFactory with LongValuesFactory with FloatValuesFactory with DoubleValuesFactory
    type ValuesFactory = PrimitiveValuesFactory with ReferenceValuesFactory with ExceptionsFactory with TypedValuesFactory
    type TargetDomain = ValuesDomain with ValuesFactory

    type PC = org.opalj.br.PC
    type PCs = org.opalj.br.PCs
    final def NoPCs = org.opalj.br.NoPCs

    /**
     * A value of type `ValueOrigin` identifies the origin of a value. In most cases the
     * value is equal to the program counter of the instruction that created the value.
     * However, for the values passed to a method, the index is conceptually:
     *  `-1-(isStatic ? 0 : 1)-(the index of the parameter adjusted by the computational
     * type of the previous parameters)`.
     *
     * For example, in case of an instance method with the signature:
     * {{{
     * public void (double d/*parameter index:0*/, Object o/*parameter index:1*/){...}
     * }}}
     *
     *  - The value `-1` is used to identify the implicit `this` reference.
     *
     *  - The value `-2` identifies the value of the parameter `d`.
     *
     *  - The value `-4` identifies the parameter `o`. (The parameter `d` is a value of
     * computational-type category 2 and needs two stack/operands values.)
     *
     * The range of standard value origins is: [-257,65535]. Hence, whenever a value of
     * type `ValueOrigin` is required/is expected it is possible to use a value with
     * type `PC` unless the program counter identifies the start of a subroutine
     * ([[SUBROUTINE_START]], [[SUBROUTINE_END]], [[SUBROUTINE]]).
     *
     * Recall that the maximum size of the method
     * parameters array is 255. If necessary, the first slot is required for the `this`
     * reference. Furthermore, for `long` and `double` values two slots are necessary; hence
     * the smallest number used to encode that the value is an actual parameter is
     * `-256`.
     *
     * The value `-257` is used to encode that the origin of the value is out
     * of the scope of the analyzed program ([[ConstantValueOrigin]]). This value is
     * currently only used for the implicit value of `IF_XXX` instructions.
     *
     * Values in the range [ [[SpecialValuesOriginOffset]] (`-10000000`) ,
     * [[VMLevelValuesOriginOffset]] (`-100000`) ] are used to identify values that are
     * created by the VM while evaluating the instruction with the `pc = origin+100000`.
     *
     * @see For further information see [[isVMLevelValue]],
     *      [[ValueOriginForVMLevelValue]], [[PCOfVMLevelValue]].
     */
    type ValueOrigin = Int

    /**
     * Identifies the upper bound for those origin values that encode origin
     * information about VM level values.
     */
    final val VMLevelValuesOriginOffset /*: ValueOrigin*/ = -100000

    /**
     * Identifies the upper bound for those origin values that encode special information.
     */
    final val SpecialValuesOriginOffset /*: ValueOrigin*/ = -10000000

    /**
     * Returns `true` if the value with the given origin was (implicitly) created
     * by the JVM while executing an instruction with the program counter
     * [[PCOfVMLevelValue]]`(origin)`.
     *
     * @see [[ValueOriginForVMLevelValue]] for further information.
     */
    final def isVMLevelValue(origin: ValueOrigin): Boolean =
        origin <= VMLevelValuesOriginOffset && origin > -SpecialValuesOriginOffset

    /**
     * Creates the origin information for a VM level value (typically an exception) that
     * was (implicitly) created while evaluating the instruction with the given
     * program counter (`pc`).
     *
     * @see [[PCOfVMLevelValue]] for further information.
     */
    final def ValueOriginForVMLevelValue(pc: PC): ValueOrigin = {
        val origin = VMLevelValuesOriginOffset - pc
        assert(
            origin <= VMLevelValuesOriginOffset,
            s"[pc:$pc] origin($origin) > VMLevelValuesOriginOffset($VMLevelValuesOriginOffset)"
        )
        assert(origin > SpecialValuesOriginOffset)
        origin
    }

    /**
     * Returns the program counter (`pc`) of the instruction that (implicitly) led to the
     * creation of the VM level value (typically an `Exception`).
     *
     * @see [[ValueOriginForVMLevelValue]] for further information.
     */
    final def PCOfVMLevelValue(origin: ValueOrigin): PC = {
        assert(origin <= VMLevelValuesOriginOffset)
        origin + VMLevelValuesOriginOffset
    }

    /**
     * Used to identify that the origin of the value is outside of the program.
     *
     * For example, the VM sometimes performs comparisons against predetermined fixed
     * values (specified in the JVM Spec.). The origin associated with such values is
     * determined by this value.
     */
    final val ConstantValueOrigin /*: ValueOrigin*/ = -257

    /**
     * Special value that is added to the ''work list''/''list of evaluated instructions''
     * before the '''program counter of the first instruction''' of a subroutine.
     *
     * The marker [[SUBROUTINE]] is used to mark the place in the worklist where we
     * start having information about subroutines.
     */
    // Some value smaller than -65536 to avoid confusion with local variable indexes.
    final val SUBROUTINE_START = -80000008

    /**
     * Special value that is added to the list of `evaluated instructions`
     * to mark the end of the evaluation of a subroutine. (I.e., this value
     * is not directly used by the AI during the interpretation, but to record the
     * progress.)
     */
    final val SUBROUTINE_END = -88888888

    /**
     * A special value that is larger than all other values used to mark boundaries
     * and information related to the handling of subroutines and which is smaller
     * that all other regular values.
     */
    final val SUBROUTINE_INFORMATION_BLOCK_SEPARATOR_BOUND = -80000000

    final val SUBROUTINE_RETURN_ADDRESS_LOCAL_VARIABLE = -88880008

    final val SUBROUTINE_RETURN_TO_TARGET = -80008888

    /**
     * Special value that is added to the work list to mark the beginning of a
     * subroutine call.
     */
    final val SUBROUTINE = -90000009 // some value smaller than -2^16

    type Operands[T >: Null <: ValuesDomain#DomainValue] = FastList[T]
    type AnOperandsArray[T >: Null <: ValuesDomain#DomainValue] = Array[Operands[T]]
    type TheOperandsArray[T >: Null <: d.Operands forSome { val d: ValuesDomain }] = Array[T]

    type Locals[T >: Null <: ValuesDomain#DomainValue] = org.opalj.collection.mutable.Locals[T]
    type ALocalsArray[T >: Null <: ValuesDomain#DomainValue] = Array[Locals[T]]
    type TheLocalsArray[T >: Null <: d.Locals forSome { val d: ValuesDomain }] = Array[T]

    /**
     * Creates a human-readable textual representation of the current memory layout.
     */
    def memoryLayoutToText(
        domain: Domain
    )(
        operandsArray: domain.OperandsArray,
        localsArray:   domain.LocalsArray
    ): String = {
        (
            for {
                ((operands, locals), pc) ← operandsArray.zip(localsArray).zipWithIndex
                if operands != null /*|| locals != null*/
            } yield {
                val localsWithIndex =
                    for {
                        (local, index) ← locals.zipWithIndex
                        if local ne null
                    } yield {
                        "("+index+":"+local+")"
                    }

                "PC: "+pc + operands.mkString("\n\tOperands: ", " <- ", "") +
                    localsWithIndex.mkString("\n\tLocals: [", ",", "]")
            }
        ).mkString("Operands and Locals: \n", "\n", "\n")
    }

    /**
     * Calculates the initial "ValueOrigin" associated with a method's parameter.
     *
     * @param isStaticMethod True if method is static and, hence, has no implicit
     *      parameter for `this`.
     * @see [[mapOperandsToParameters]]
     */
    def parameterToValueIndex(
        isStaticMethod: Boolean,
        descriptor:     MethodDescriptor,
        parameterIndex: Int
    ): Int = {

        def origin(localVariableIndex: Int) = -localVariableIndex - 1

        var localVariableIndex = 0

        if (!isStaticMethod) {
            localVariableIndex += 1 /*=="this".computationalType.operandSize*/
        }
        val parameterTypes = descriptor.parameterTypes
        var currentIndex = 0
        while (currentIndex < parameterIndex) {
            localVariableIndex += parameterTypes(currentIndex).computationalType.operandSize
            currentIndex += 1
        }
        origin(localVariableIndex)
    }

    /**
     * Maps a list of operands (e.g., as passed to the `invokeXYZ` instructions) to
     * the list of parameters for the given method. The parameters are stored in the
     * local variables ([[Locals]])/registers of the method; i.e., this method
     * creates an initial assignment for the local variables that can directly
     * be used to pass them to [[AI]]'s
     * `perform(...)(<initialOperands = Nil>,initialLocals)` method.
     *
     * @param operands The list of operands used to call the given method. The length
     *      of the list must be:
     *      {{{
     *      calledMethod.descriptor.parametersCount + { if (calledMethod.isStatic) 0 else 1 }
     *      }}}.
     *      I.e., the list of operands must contain one value per parameter and – 
     *      in case of instance methods – the receiver object. The list __must not
     *       contain additional values__. The latter is automatically ensured if this
     *      method is called (in)directly by [[AI]] and the operands were just passed
     *      through.
     *      If two or more operands are (reference) identical then the adaptation will only
     *      be performed once and the adapted value will be reused; this ensures that
     *      the relation between values remains stable.
     * @param calledMethod The method that will be evaluated using the given operands.
     * @param targetDomain The [[Domain]] that will be use to perform the abstract
     *      interpretation.
     */
    def mapOperandsToParameters[D <: ValuesDomain](
        operands:     Operands[D#DomainValue],
        calledMethod: Method,
        targetDomain: ValuesDomain with ValuesFactory
    ): Locals[targetDomain.DomainValue] = {

        assert(
            operands.size == calledMethod.parametersCount,
            { if (calledMethod.isStatic) "static" else "/*virtual*/" } +
                s" ${calledMethod.toJava()}(Declared Parameters: ${calledMethod.parametersCount}) "+
                s"${operands.mkString("Operands(", ",", ")")}"
        )

        import org.opalj.collection.mutable.Locals
        implicit val domainValue = targetDomain.DomainValue
        val parameters = Locals[targetDomain.DomainValue](calledMethod.body.get.maxLocals)
        var localVariableIndex = 0
        var processedOperands = 0
        val operandsInParameterOrder = operands.reverse
        operandsInParameterOrder foreach { operand ⇒
            val parameter = {
                // Was the same value (determined by "eq") already adapted?
                // If so, we reuse it to facilitate correlation analyses
                var pOperands = operandsInParameterOrder
                var pOperandIndex = 0
                var pLocalVariableIndex = 0
                while (pOperandIndex < processedOperands && (pOperands.head ne operand)) {
                    pOperandIndex += 1
                    pLocalVariableIndex += pOperands.head.computationalType.operandSize
                    pOperands = pOperands.tail
                }
                if (pOperandIndex < processedOperands) {
                    parameters(pLocalVariableIndex)
                } else {
                    // the value was not previously adapted
                    operand.adapt(targetDomain, -(processedOperands + 1))
                }
            }
            parameters(localVariableIndex) = parameter
            processedOperands += 1
            localVariableIndex += operand.computationalType.operandSize
        }

        parameters
    }

    /**
     * Maps the operands to the target domain while ensuring that two operands that
     * are identical are identical afterwards.
     */
    def mapOperands(
        theOperands:  Operands[_ <: ValuesDomain#DomainValue],
        targetDomain: ValuesDomain with ValuesFactory
    ): Array[targetDomain.DomainValue] = {
        implicit val domainValue = targetDomain.DomainValue

        val operandsCount = theOperands.size
        val adaptedOperands = new Array[targetDomain.DomainValue](operandsCount)
        val processedOperands = new Array[Object](operandsCount)
        var remainingOperands = theOperands
        var i = 0
        def getIndex(operand: Object): Int = {
            var ii = 0
            while (ii < i) {
                if (processedOperands(i) eq operand)
                    return i;
                ii += 1
            }
            -1 // not found
        }

        while (remainingOperands.nonEmpty) {
            val nextOperand = remainingOperands.head
            val previousOperandIndex = getIndex(nextOperand)
            if (previousOperandIndex == -1)
                adaptedOperands(i) = nextOperand.adapt(targetDomain, i)
            else
                adaptedOperands(i) = adaptedOperands(previousOperandIndex)

            i += 1
            remainingOperands = remainingOperands.tail
        }

        adaptedOperands
    }

    /**
     * Collects the result of a match of a partial function against an instruction's
     * operands.
     */
    def collectPCWithOperands[B](
        domain: ValuesDomain
    )(
        code: Code, operandsArray: domain.OperandsArray
    )(
        f: PartialFunction[(PC, Instruction, domain.Operands), B]
    ): Seq[B] = {
        val instructions = code.instructions
        val max_pc = instructions.size
        var pc = 0
        var result: List[B] = List.empty
        while (pc < max_pc) {
            val instruction = instructions(pc)
            val operands = operandsArray(pc)
            if (operands ne null) {
                val params = (pc, instruction, operands)
                if (f.isDefinedAt(params)) {
                    result = f(params) :: result
                }
            }
            pc = instruction.indexOfNextInstruction(pc)(code)
        }
        result.reverse
    }

    def foreachPCWithOperands[U](
        domain: ValuesDomain
    )(
        code: Code, operandsArray: domain.OperandsArray
    )(
        f: Function3[PC, Instruction, domain.Operands, U]
    ): Unit = {
        val instructions = code.instructions
        val max_pc = instructions.size
        var pc = 0
        while (pc < max_pc) {
            val instruction = instructions(pc)
            val operands = operandsArray(pc)
            if (operands ne null) {
                f(pc, instruction, operands)
            }
            pc = instruction.indexOfNextInstruction(pc)(code)
        }
    }

    type ExceptionsRaisedByCalledMethod = ExceptionsRaisedByCalledMethods.Value
}
