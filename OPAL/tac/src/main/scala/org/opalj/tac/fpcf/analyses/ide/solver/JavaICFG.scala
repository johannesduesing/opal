/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj
package tac
package fpcf
package analyses
package ide
package solver

import org.opalj.br.Method
import org.opalj.ide.solver.ICFG

/**
 * Interprocedural control flow graph for Java programs
 */
trait JavaICFG extends ICFG[JavaStatement, Method]
