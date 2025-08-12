/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj
package tac
package fpcf
package analyses
package ide
package instances
package lcp_on_fields

import org.opalj.br.analyses.SomeProject
import org.opalj.fpcf.PropertyBounds
import org.opalj.tac.fpcf.analyses.ide.instances.lcp_on_fields.problem.LinearConstantPropagationProblemExtended
import org.opalj.tac.fpcf.analyses.ide.instances.linear_constant_propagation.LinearConstantPropagationPropertyMetaInformation
import org.opalj.tac.fpcf.analyses.ide.instances.linear_constant_propagation.problem.LinearConstantPropagationFact
import org.opalj.tac.fpcf.analyses.ide.instances.linear_constant_propagation.problem.LinearConstantPropagationValue
import org.opalj.tac.fpcf.analyses.ide.integration.JavaIDEAnalysisScheduler
import org.opalj.tac.fpcf.analyses.ide.integration.JavaIDEAnalysisSchedulerBase
import org.opalj.tac.fpcf.analyses.ide.integration.JavaIDEPropertyMetaInformation
import org.opalj.tac.fpcf.analyses.ide.problem.JavaIDEProblem
import org.opalj.tac.fpcf.analyses.ide.solver.JavaICFG

/**
 * Extended linear constant propagation as IDE analysis, trying to resolve field accesses with the LCP on fields
 * analysis (see [[LCPOnFieldsAnalysisScheduler]]).
 *
 * @author Robin Körkemeier
 */
class LinearConstantPropagationAnalysisSchedulerExtended
    extends JavaIDEAnalysisScheduler[LinearConstantPropagationFact, LinearConstantPropagationValue]
    with JavaIDEAnalysisSchedulerBase.ForwardICFG {
    override def propertyMetaInformation: JavaIDEPropertyMetaInformation[
        LinearConstantPropagationFact,
        LinearConstantPropagationValue
    ] = LinearConstantPropagationPropertyMetaInformation

    override def createProblem(project: SomeProject, icfg: JavaICFG): JavaIDEProblem[
        LinearConstantPropagationFact,
        LinearConstantPropagationValue
    ] = {
        new LinearConstantPropagationProblemExtended
    }

    override def uses: Set[PropertyBounds] =
        super.uses + PropertyBounds.ub(LCPOnFieldsPropertyMetaInformation)
}
