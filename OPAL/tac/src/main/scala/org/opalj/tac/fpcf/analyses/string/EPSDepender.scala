/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj
package tac
package fpcf
package analyses
package string

import org.opalj.fpcf.SomeEOptionP

/**
 * @author Maximilian Rüsch
 */
private[string] case class EPSDepender[T <: ASTNode[V]](
    instr:     T,
    pc:        Int,
    state:     DUSiteState,
    dependees: Seq[SomeEOptionP]
) {

    def withDependees(newDependees: Seq[SomeEOptionP]): EPSDepender[T] = EPSDepender(
        instr,
        pc,
        state,
        newDependees
    )
}
