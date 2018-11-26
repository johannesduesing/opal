/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj.fpcf.analyses.string_definition

import org.opalj.br.analyses.SomeProject
import org.opalj.fpcf.FPCFAnalysis
import org.opalj.fpcf.PropertyComputationResult
import org.opalj.fpcf.PropertyKind
import org.opalj.fpcf.PropertyStore
import org.opalj.fpcf.properties.StringConstancyProperty
import org.opalj.fpcf.FPCFLazyAnalysisScheduler
import org.opalj.fpcf.ComputationSpecification
import org.opalj.fpcf.NoResult
import org.opalj.fpcf.analyses.string_definition.preprocessing.AbstractPathFinder
import org.opalj.fpcf.analyses.string_definition.preprocessing.DefaultPathFinder
import org.opalj.fpcf.analyses.string_definition.preprocessing.PathTransformer
import org.opalj.fpcf.Result
import org.opalj.fpcf.analyses.string_definition.interpretation.InterpretationHandler
import org.opalj.tac.SimpleTACAIKey
import org.opalj.tac.Stmt

class StringTrackingAnalysisContext(
        val stmts: Array[Stmt[V]]
)

/**
 * LocalStringDefinitionAnalysis processes a read operation of a local string variable at a program
 * position, ''pp'', in a way that it finds the set of possible strings that can be read at ''pp''.
 *
 * "Local" as this analysis takes into account only the enclosing function as a context. Values
 * coming from other functions are regarded as dynamic values even if the function returns a
 * constant string value. [[StringConstancyProperty]] models this by inserting "*" into the set of
 * possible strings.
 *
 * StringConstancyProperty might contain more than one possible string, e.g., if the source of the
 * value is an array.
 *
 * @author Patrick Mell
 */
class LocalStringDefinitionAnalysis(
        val project: SomeProject
) extends FPCFAnalysis {

    def analyze(data: P): PropertyComputationResult = {
        val tacProvider = p.get(SimpleTACAIKey)
        val stmts = tacProvider(data._2).stmts
        val cfg = tacProvider(data._2).cfg

        val defSites = data._1.definedBy.toArray.sorted
        val expr = stmts(defSites.head).asAssignment.expr
        val pathFinder: AbstractPathFinder = new DefaultPathFinder()
        if (InterpretationHandler.isStringBuilderToStringCall(expr)) {
            val initDefSites = InterpretationHandler.findDefSiteOfInit(
                expr.asVirtualFunctionCall, stmts
            )
            if (initDefSites.isEmpty) {
                throw new IllegalStateException("did not find any initializations!")
            }

            val paths = pathFinder.findPaths(initDefSites, data._1.definedBy.head, cfg)
            val leanPaths = paths.makeLeanPath(data._1, stmts)
            // The following case should only occur if an object is queried that does not occur at
            // all within the CFG
            if (leanPaths.isEmpty) {
                return NoResult
            }

            val tree = new PathTransformer(cfg).pathToStringTree(leanPaths.get)
            if (tree.isDefined) {
                Result(data, StringConstancyProperty(tree.get))
            } else {
                NoResult
            }
        } // If not a call to String{Builder, Buffer}.toString, then we deal with pure strings
        else {
            val paths = pathFinder.findPaths(defSites.toList, data._1.definedBy.head, cfg)
            if (paths.elements.isEmpty) {
                NoResult
            } else {
                val tree = new PathTransformer(cfg).pathToStringTree(paths)
                if (tree.isDefined) {
                    Result(data, StringConstancyProperty(tree.get))
                } else {
                    NoResult
                }
            }
        }
    }

}

sealed trait LocalStringDefinitionAnalysisScheduler extends ComputationSpecification {

    final override def derives: Set[PropertyKind] = Set(StringConstancyProperty)

    final override def uses: Set[PropertyKind] = {
        Set()
    }

    final override type InitializationData = Null

    final def init(p: SomeProject, ps: PropertyStore): Null = null

    def beforeSchedule(p: SomeProject, ps: PropertyStore): Unit = {}

    def afterPhaseCompletion(p: SomeProject, ps: PropertyStore): Unit = {}

}

/**
 * Executor for the lazy analysis.
 */
object LazyStringDefinitionAnalysis
    extends LocalStringDefinitionAnalysisScheduler
    with FPCFLazyAnalysisScheduler {

    final override def startLazily(
        p: SomeProject, ps: PropertyStore, unused: Null
    ): FPCFAnalysis = {
        val analysis = new LocalStringDefinitionAnalysis(p)
        ps.registerLazyPropertyComputation(StringConstancyProperty.key, analysis.analyze)
        analysis
    }

}