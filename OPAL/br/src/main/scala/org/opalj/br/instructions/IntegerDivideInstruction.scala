/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj
package br
package instructions

/**
 * An instruction that divides two primitive integer values (long or in).
 *
 * @author Michael Eichberg
 */
abstract class IntegerDivideInstruction extends DivideInstruction {

    final def jvmExceptions: List[ObjectType] = ArithmeticInstruction.jvmExceptions

    final def mayThrowExceptions: Boolean = true

    final def nextInstructions(
        currentPC:             PC,
        regularSuccessorsOnly: Boolean
    )(
        implicit
        code:           Code,
        classHierarchy: ClassHierarchy = ClassHierarchy.PreInitializedClassHierarchy
    ): List[PC] = {
        if (regularSuccessorsOnly)
            List(indexOfNextInstruction(currentPC))
        else
            Instruction.nextInstructionOrExceptionHandler(
                this,
                currentPC,
                ObjectType.ArithmeticException
            )
    }

}
