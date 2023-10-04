/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj
package tac
package fpcf
package analyses
package fieldassignability

import scala.annotation.switch
import scala.collection.mutable
import org.opalj.br.Method
import org.opalj.br.PCs
import org.opalj.br.analyses.SomeProject
import org.opalj.br.fpcf.BasicFPCFEagerAnalysisScheduler
import org.opalj.br.fpcf.BasicFPCFLazyAnalysisScheduler
import org.opalj.br.fpcf.FPCFAnalysis
import org.opalj.fpcf.PropertyBounds
import org.opalj.fpcf.PropertyStore
import org.opalj.br.fpcf.properties.immutability.FieldAssignability
import org.opalj.br.fpcf.properties.immutability.LazilyInitialized
import org.opalj.RelationalOperators.EQ
import org.opalj.RelationalOperators.NE
import org.opalj.br.DeclaredField
import org.opalj.br.DefinedMethod
import org.opalj.br.cfg.BasicBlock
import org.opalj.br.cfg.CFGNode
import org.opalj.collection.immutable.IntTrieSet
import org.opalj.br.ObjectType
import org.opalj.br.FieldType
import org.opalj.br.fpcf.properties.immutability.Assignable
import org.opalj.br.fpcf.properties.immutability.UnsafelyLazilyInitialized
import org.opalj.br.Field
import org.opalj.br.fpcf.properties.cg.Callers
import org.opalj.br.PC
import org.opalj.br.fpcf.properties.fieldaccess.AccessParameter
import org.opalj.br.fpcf.properties.fieldaccess.AccessReceiver
import org.opalj.br.fpcf.properties.fieldaccess.FieldReadAccessInformation
import org.opalj.br.fpcf.properties.fieldaccess.FieldWriteAccessInformation
import org.opalj.br.fpcf.properties.fieldaccess.NoFieldReadAccessInformation
import org.opalj.br.fpcf.properties.fieldaccess.NoFieldWriteAccessInformation
import org.opalj.fpcf.EOptionP
import org.opalj.fpcf.SomeEOptionP
import org.opalj.fpcf.UBP
import org.opalj.tac.CaughtException
import org.opalj.tac.ClassConst
import org.opalj.tac.Compare
import org.opalj.tac.Expr
import org.opalj.tac.FieldWriteAccessStmt
import org.opalj.tac.GetField
import org.opalj.tac.GetStatic
import org.opalj.tac.If
import org.opalj.tac.MonitorEnter
import org.opalj.tac.MonitorExit
import org.opalj.tac.PrimitiveTypecastExpr
import org.opalj.tac.SelfReferenceParameter
import org.opalj.tac.Stmt
import org.opalj.tac.TACMethodParameter
import org.opalj.tac.TACode
import org.opalj.tac.Throw
import org.opalj.tac.VirtualFunctionCall
import org.opalj.tac.fpcf.analyses.cg.uVarForDefSites

/**
 *
 * Determines the assignability of a field.
 *
 * @note Requires that the 3-address code's expressions are not deeply nested.
 * @author Tobias Roth
 * @author Dominik Helm
 * @author Florian Kübler
 * @author Michael Eichberg
 *
 */
class L2FieldAssignabilityAnalysis private[analyses] (val project: SomeProject)
    extends AbstractFieldAssignabilityAnalysis
    with FPCFAnalysis {

    val considerLazyInitialization: Boolean =
        project.config.getBoolean(
            "org.opalj.fpcf.analyses.L2FieldAssignabilityAnalysis.considerLazyInitialization"
        )

    /**
     * Analyzes field writes for a single method, returning false if the field may still be
     * effectively final and true otherwise.
     */
    def methodUpdatesField(
        definedMethod: DefinedMethod,
        taCode:        TACode[TACMethodParameter, V],
        callers:       Callers,
        pc:            PC,
        receiver:      AccessReceiver,
        value:         AccessParameter
    )(implicit state: AnalysisState): Boolean = {
        val field = state.field
        val method = definedMethod.definedMethod
        val stmts = taCode.stmts
        val receiverVar = receiver.map(uVarForDefSites(_, taCode.pcToIndex))

        val index = taCode.pcToIndex(pc)
        if (method.isInitializer) {
            if (field.isStatic) {
                method.isConstructor
            } else {
                receiverVar.isDefined && receiverVar.get.definedBy != SelfReferenceParameter
            }
        } else {
            if (field.isStatic ||
                receiverVar.isDefined && receiverVar.get.definedBy == SelfReferenceParameter) {
                // We consider lazy initialization if there is only single write
                // outside an initializer, so we can ignore synchronization
                state.fieldAssignability == LazilyInitialized ||
                    state.fieldAssignability == UnsafelyLazilyInitialized ||
                    // A lazily initialized instance field must be initialized only
                    // by its owning instance
                    !field.isStatic &&
                    receiverVar.isDefined && receiverVar.get.definedBy != SelfReferenceParameter ||
                    // A field written outside an initializer must be lazily
                    // initialized or it is assignable
                    {
                        if (considerLazyInitialization) {
                            val result = isAssignable(
                                index,
                                getDefaultValues(),
                                method,
                                taCode,
                                value
                            )
                            result
                        } else
                            true
                    }
            } else if (receiverVar.isDefined && !referenceHasNotEscaped(receiverVar.get, stmts, definedMethod, callers)) {
                // Here the clone pattern is determined among others
                //
                // note that here we assume real three address code (flat hierarchy)

                // for instance fields it is okay if they are written in the
                // constructor (w.r.t. the currently initialized object!)

                // If the field that is written is not the one referred to by the
                // self reference, it is not effectively final.

                // However, a method (e.g. clone) may instantiate a new object and
                // write the field as long as that new object did not yet escape.
                true
            } else {
                val fieldWriteAccessInformation = state.latestMethodSpecificFieldWriteAccessInformation match { // TODO fix and continuation
                    case Some(UBP(fai)) => fai
                    case _ =>
                        // Field access information should have already been written to the state by the abstract analysis
                        throw new IllegalStateException("No field write access information encountered even though expected")
                }

                val writes = fieldWriteAccessInformation.accesses
                val writesInMethod = writes.filter(w => contextProvider.contextFromId(w._1).method eq definedMethod)

                if (writesInMethod.distinctBy(_._1).size > 1)
                    return true; // Field is written in multiple locations, thus must be assignable

                // If we have no information about the receiver, we soundly return
                if (receiverVar.isEmpty)
                    return true;
                val assignedValueObject = receiverVar.get
                if (assignedValueObject.definedBy.exists(_ < 0))
                    return true;
                val assignedValueObjectVar =
                    stmts(assignedValueObject.definedBy.head).asAssignment.targetVar.asVar

                val fieldWriteInMethodIndex = taCode.pcToIndex(writesInMethod.next()._2)
                if (assignedValueObjectVar != null && !assignedValueObjectVar.usedBy.forall { index =>
                    val stmt = stmts(index)

                    fieldWriteInMethodIndex == index || // The value is itself written to another object
                        // IMPROVE: Can we use field access information to care about reflective accesses here?
                        stmt.isPutField && stmt.asPutField.name != state.field.name ||
                        stmt.isAssignment && stmt.asAssignment.targetVar == assignedValueObjectVar ||
                        stmt.isMethodCall && stmt.asMethodCall.name == "<init>" ||
                        dominates(fieldWriteInMethodIndex, index, taCode)
                })
                    return true;

                val fraiEP = propertyStore(declaredFields(state.field), FieldReadAccessInformation.key) // TODO fix and continuation
                val fieldReadAccessInformation = if (fraiEP.hasUBP) fraiEP.ub else NoFieldReadAccessInformation
                val fieldReadsInMethod = fieldReadAccessInformation
                    .accesses
                    .filter(a => contextProvider.contextFromId(a._1).method eq definedMethod)
                    .map(_._2)
                if (!fieldReadsInMethod.forall { pc =>
                    val index = taCode.pcToIndex(pc)
                    fieldWriteInMethodIndex == index ||
                        dominates(fieldWriteInMethodIndex, index, taCode)
                })
                    return true;
                false
            }

        }
    }

    //lazy initialization:

    case class State(
            field: Field
    ) extends AbstractFieldAssignabilityAnalysisState {
        // TODO add dependency management for continuation for method specific reads and writes

        var latestMethodSpecificFieldReadAccessInformation: Option[EOptionP[DeclaredField, FieldReadAccessInformation]] = None
        var latestMethodSpecificFieldWriteAccessInformation: Option[EOptionP[DeclaredField, FieldWriteAccessInformation]] = None

        override def hasDependees: Boolean = {
            latestMethodSpecificFieldReadAccessInformation.exists(_.isRefinable) ||
                latestMethodSpecificFieldWriteAccessInformation.exists(_.isRefinable) ||
                super.hasDependees
        }

        override def dependees: Set[SomeEOptionP] = {
            super.dependees ++
                latestMethodSpecificFieldReadAccessInformation.filter(_.isRefinable) ++
                latestMethodSpecificFieldWriteAccessInformation.filter(_.isRefinable)
        }
    }

    type AnalysisState = State

    override def createState(field: Field): AnalysisState = State(field)

    /**
     * Determines whether the basic block of a given index dominates the basic block of the other index or is executed
     * before the other index in the case of both indexes belonging to the same basic block.
     */
    def dominates(
        potentiallyDominatorIndex: Int,
        potentiallyDominatedIndex: Int,
        taCode:                    TACode[TACMethodParameter, V]
    ): Boolean = {
        val bbPotentiallyDominator = taCode.cfg.bb(potentiallyDominatorIndex)
        val bbPotentiallyDominated = taCode.cfg.bb(potentiallyDominatedIndex)
        taCode.cfg.dominatorTree
            .strictlyDominates(bbPotentiallyDominator.nodeId, bbPotentiallyDominated.nodeId) ||
            bbPotentiallyDominator == bbPotentiallyDominated && potentiallyDominatorIndex < potentiallyDominatedIndex
    }

    /**
     * Handles the lazy initialization determination for a field write in a given method
     * @author Tobias Roth
     * @return true if no lazy initialization was recognized
     */
    def isAssignable(
        writeIndex:    Int,
        defaultValues: Set[Any],
        method:        Method,
        taCode:        TACode[TACMethodParameter, V],
        value:         AccessParameter
    )(implicit state: AnalysisState): Boolean = {
        state.fieldAssignability =
            determineLazyInitialization(writeIndex, defaultValues, method, taCode, value)
        state.fieldAssignability == Assignable
    }

    /**
     * Determines the kind of lazy initialization of a given field in the given method through a given field write.
     * @author Tobias Roth
     */
    def determineLazyInitialization(
        writeIndex:    Int,
        defaultValues: Set[Any],
        method:        Method,
        taCode:        TACode[TACMethodParameter, V],
        value:         AccessParameter
    )(implicit state: AnalysisState): FieldAssignability = {
        val code = taCode.stmts
        val cfg = taCode.cfg
        val write = code(writeIndex).asFieldWriteAccessStmt
        val writeBB = cfg.bb(writeIndex).asBasicBlock
        val resultCatchesAndThrows = findCatchesAndThrows(taCode)

        /**
         * Determines whether all caught exceptions are thrown afterwards
         */
        def noInterferingExceptions(): Boolean =
            resultCatchesAndThrows._1.forall {
                case (catchPC, originsCaughtException) =>
                    resultCatchesAndThrows._2.exists {
                        case (throwPC, throwDefinitionSites) =>
                            dominates(taCode.pcToIndex(catchPC), taCode.pcToIndex(throwPC), taCode) &&
                                throwDefinitionSites == originsCaughtException //throwing and catching same exceptions
                    }
            }

        val findGuardsResult = findGuards(writeIndex, defaultValues, taCode)

        val (readIndex, guardIndex, defaultCaseIndex, elseCaseIndex) = //guardIndex: for debugging purpose
            if (findGuardsResult.nonEmpty)
                findGuardsResult.head
            else // no guard -> no Lazy Initialization
                return Assignable;

        // The field have to be written when the guard is in the default-case branch
        if (!dominates(defaultCaseIndex, writeIndex, taCode))
            return Assignable;

        val elseBB = cfg.bb(elseCaseIndex)

        // prevents wrong control flow
        if (isTransitivePredecessor(elseBB, writeBB))
            return Assignable;

        if (method.returnType == state.field.fieldType) {
            // prevents that another value than the field value is returned with the same type
            if (!isFieldValueReturned(write, writeIndex, readIndex, taCode, findGuardsResult))
                return Assignable;
            //prevents that the field is seen with another value
            if ( // potentially unsound with method.returnType == state.field.fieldType
            // TODO comment it out and look at appearing cases
            taCode.stmts.exists(
                stmt =>
                    stmt.isReturnValue && !isTransitivePredecessor(
                        writeBB,
                        cfg.bb(taCode.pcToIndex(stmt.pc))
                    ) &&
                        findGuardsResult.forall { //TODO check...
                            case (indexOfFieldRead, _, _, _) =>
                                !isTransitivePredecessor(
                                    cfg.bb(indexOfFieldRead),
                                    cfg.bb(taCode.pcToIndex(stmt.pc))
                                )
                        }
            ))
                return Assignable;
        }

        val fwaiEP = propertyStore(declaredFields(state.field), FieldWriteAccessInformation.key) // TODO fix and continuation
        val fieldWriteAccessInformation = if (fwaiEP.hasUBP) fwaiEP.ub else NoFieldWriteAccessInformation

        val writes = fieldWriteAccessInformation.accesses

        // prevents writes outside the method and the constructor
        if (writes.exists(w => {
            val accessingMethod = contextProvider.contextFromId(w._1).method.definedMethod
            (accessingMethod ne method) && !accessingMethod.isInitializer
        })) {
            return Assignable;
        }

        if (writes.distinctBy(_._1).size < writes.size)
            return Assignable; // More than one write per method was detected

        val fraiEP = propertyStore(declaredFields(state.field), FieldReadAccessInformation.key) // TODO fix and continuation
        val fieldReadAccessInformation = if (fraiEP.hasUBP) fraiEP.ub else NoFieldReadAccessInformation
        val reads = fieldReadAccessInformation.accesses

        // prevents reads outside the method
        if (reads.exists(r => contextProvider.contextFromId(r._1).method.definedMethod ne method))
            return Assignable;

        if (reads.iterator
            .exists(a => {
                var seen: Set[Stmt[V]] = Set.empty
                def doUsesEscape(pcs: PCs): Boolean = {
                    pcs.exists(pc => {
                        val index = taCode.pcToIndex(pc)
                        if (index == -1)
                            return true;
                        val stmt2 = taCode.stmts(index)

                        if (stmt2.isAssignment) {
                            stmt2.asAssignment.targetVar.usedBy.exists(
                                i =>
                                    i == -1 || {
                                        val st = taCode.stmts(i)
                                        if (!seen.contains(st)) {
                                            seen += st
                                            !(
                                                st.isReturnValue || st.isIf ||
                                                dominates(guardIndex, i, taCode) &&
                                                isTransitivePredecessor(cfg.bb(writeIndex), cfg.bb(i)) ||
                                                st.isAssignment && {
                                                    val expr = st.asAssignment.expr
                                                    (expr.isCompare || expr.isFunctionCall && {
                                                        val functionCall = expr.asFunctionCall
                                                        state.field.fieldType match {
                                                            case ObjectType.Byte    => functionCall.name == "byteValue"
                                                            case ObjectType.Short   => functionCall.name == "shortValue"
                                                            case ObjectType.Integer => functionCall.name == "intValue"
                                                            case ObjectType.Long    => functionCall.name == "longValue"
                                                            case ObjectType.Float   => functionCall.name == "floatValue"
                                                            case ObjectType.Double  => functionCall.name == "doubleValue"
                                                            case _                  => false
                                                        }
                                                    }) && !doUsesEscape(st.asAssignment.targetVar.usedBy)
                                                }
                                            )
                                        } else false
                                    }
                            )
                        } else false
                    })
                }
                doUsesEscape(IntTrieSet(a._2))
            }))
            return Assignable;

        if (write.value.asVar.definedBy.forall { _ >= 0 } &&
            dominates(defaultCaseIndex, writeIndex, taCode) && noInterferingExceptions()) {
            if (method.isSynchronized)
                LazilyInitialized
            else {
                val (indexMonitorEnter, indexMonitorExit) = findMonitors(writeIndex, taCode)
                val monitorResultsDefined = indexMonitorEnter.isDefined && indexMonitorExit.isDefined
                if (monitorResultsDefined && dominates(indexMonitorEnter.get, readIndex, taCode))
                    LazilyInitialized
                else
                    UnsafelyLazilyInitialized
            }
        } else
            Assignable
    }

    /**
     *
     * This method returns the information about catch blocks, throw statements and return nodes
     *
     * @note It requires still determined taCode
     *
     * @return The first element of the tuple returns:
     *         the caught exceptions (the pc of the catch, the exception type, the origin of the caught exception,
     *         the bb of the caughtException)
     * @return The second element of the tuple returns:
     *         The throw statements: (the pc, the definitionSites, the bb of the throw statement)
     * @author Tobias Roth
     */
    def findCatchesAndThrows(
        tacCode: TACode[TACMethodParameter, V]
    ): (List[(Int, IntTrieSet)], List[(Int, IntTrieSet)]) = {
        var caughtExceptions: List[(Int, IntTrieSet)] = List.empty
        var throwStatements: List[(Int, IntTrieSet)] = List.empty
        for (stmt <- tacCode.stmts) {
            if (!stmt.isNop) { // to prevent the handling of partially negative pcs of nops
                (stmt.astID: @switch) match {

                    case CaughtException.ASTID =>
                        val caughtException = stmt.asCaughtException
                        caughtExceptions = (caughtException.pc, caughtException.origins) :: caughtExceptions

                    case Throw.ASTID =>
                        val throwStatement = stmt.asThrow
                        val throwStatementDefinedBys = throwStatement.exception.asVar.definedBy
                        throwStatements = (throwStatement.pc, throwStatementDefinedBys) :: throwStatements

                    case _ =>
                }
            }
        }
        (caughtExceptions, throwStatements)
    }

    /**
     * Searches the closest monitor enter and exit to the field write.
     * @return the index of the monitor enter and exit
     * @author Tobias Roth
     */
    def findMonitors(
        fieldWrite: Int,
        tacCode:    TACode[TACMethodParameter, V]
    )(implicit state: State): (Option[Int], Option[Int]) = {

        var result: (Option[Int], Option[Int]) = (None, None)
        val startBB = tacCode.cfg.bb(fieldWrite)
        var monitorExitQueuedBBs: Set[CFGNode] = startBB.successors
        var worklistMonitorExit = getSuccessors(startBB, Set.empty)

        /**
         * checks that a given monitor supports a thread safe lazy initialization.
         * Supports two ways of synchronized blocks.
         *
         * When determining the lazy initialization of a static field,
         * it allows only global locks on Foo.class. Independent of which class Foo is.
         *
         * When determining the lazy initialization of an instance fields, it allows
         * synchronized(this) and synchronized(Foo.class). Independent of which class Foo is.
         * In case of an instance field the second case is even stronger than synchronized(this).
         *
         */
        def checkMonitor(v: V)(implicit state: State): Boolean = {
            v.definedBy.forall(definedByIndex => {
                if (definedByIndex >= 0) {
                    tacCode.stmts(definedByIndex) match {
                        //synchronized(Foo.class)
                        case Assignment(_, _, _: ClassConst) => true
                        case _                               => false
                    }
                } else {
                    //synchronized(this)
                    state.field.isNotStatic && IntTrieSet(definedByIndex) == SelfReferenceParameter
                }
            })
        }

        var monitorEnterQueuedBBs: Set[CFGNode] = startBB.predecessors
        var worklistMonitorEnter = getPredecessors(startBB, Set.empty)

        //find monitorenter
        while (worklistMonitorEnter.nonEmpty) {
            val curBB = worklistMonitorEnter.head
            worklistMonitorEnter = worklistMonitorEnter.tail
            val startPC = curBB.startPC
            val endPC = curBB.endPC
            var hasNotFoundAnyMonitorYet = true
            for (i <- startPC to endPC) {
                (tacCode.stmts(i).astID: @switch) match {
                    case MonitorEnter.ASTID =>
                        val monitorEnter = tacCode.stmts(i).asMonitorEnter
                        if (checkMonitor(monitorEnter.objRef.asVar)) {
                            result = (Some(tacCode.pcToIndex(monitorEnter.pc)), result._2)
                            hasNotFoundAnyMonitorYet = false
                        }
                    case _ =>
                }
            }
            if (hasNotFoundAnyMonitorYet) {
                val predecessor = getPredecessors(curBB, monitorEnterQueuedBBs)
                worklistMonitorEnter ++= predecessor
                monitorEnterQueuedBBs ++= predecessor
            }
        }
        //find monitorexit
        while (worklistMonitorExit.nonEmpty) {
            val curBB = worklistMonitorExit.head

            worklistMonitorExit = worklistMonitorExit.tail
            val endPC = curBB.endPC

            val cfStmt = tacCode.stmts(endPC)
            (cfStmt.astID: @switch) match {

                case MonitorExit.ASTID =>
                    val monitorExit = cfStmt.asMonitorExit
                    if (checkMonitor(monitorExit.objRef.asVar)) {
                        result = (result._1, Some(tacCode.pcToIndex(monitorExit.pc)))
                    }

                case _ =>
                    val successors = getSuccessors(curBB, monitorExitQueuedBBs)
                    worklistMonitorExit ++= successors
                    monitorExitQueuedBBs ++= successors
            }
        }
        result
    }

    /**
     * Finds the indexes of the guarding if-Statements for a lazy initialization, the index of the
     * first statement executed if the field does not have its default value and the index of the
     * field read used for the guard and the index of the field-read.
     */
    def findGuards(
        fieldWrite:    Int,
        defaultValues: Set[Any],
        taCode:        TACode[TACMethodParameter, V]
    )(implicit state: State): List[(Int, Int, Int, Int)] = {
        val cfg = taCode.cfg
        val code = taCode.stmts

        val startBB = cfg.bb(fieldWrite).asBasicBlock

        var enqueuedBBs: Set[CFGNode] = startBB.predecessors
        var worklist: List[BasicBlock] = getPredecessors(startBB, Set.empty)
        var seen: Set[BasicBlock] = Set.empty
        var result: List[(Int, Int, Int)] = List.empty

        while (worklist.nonEmpty) {
            val curBB = worklist.head
            worklist = worklist.tail
            if (!seen.contains(curBB)) {
                seen += curBB

                val endPC = curBB.endPC

                val cfStmt = code(endPC)
                (cfStmt.astID: @switch) match {

                    case If.ASTID =>
                        val ifStmt = cfStmt.asIf
                        if (ifStmt.condition.equals(EQ) && curBB != startBB && isGuard(
                            ifStmt,
                            defaultValues,
                            code,
                            taCode
                        )) {
                            result = (endPC, ifStmt.targetStmt, endPC + 1) :: result
                        } else if (ifStmt.condition.equals(NE) && curBB != startBB && isGuard(
                            ifStmt,
                            defaultValues,
                            code,
                            taCode
                        )) {
                            result = (endPC, endPC + 1, ifStmt.targetStmt) :: result
                        } else {
                            if ((cfg.bb(fieldWrite) != cfg.bb(ifStmt.target) || fieldWrite < ifStmt.target) &&
                                isTransitivePredecessor(cfg.bb(fieldWrite), cfg.bb(ifStmt.target))) {
                                return List.empty //in cases where other if-statements destroy
                            }
                        }
                        val predecessors = getPredecessors(curBB, enqueuedBBs)
                        worklist ++= predecessors
                        enqueuedBBs ++= predecessors

                    // Otherwise, we have to ensure that a guard is present for all predecessors
                    case _ =>
                        val predecessors = getPredecessors(curBB, enqueuedBBs)
                        worklist ++= predecessors
                        enqueuedBBs ++= predecessors
                }
            }

        }

        var finalResult: List[(Int, Int, Int, Int)] = List.empty
        var fieldReadIndex = 0
        result.foreach(result => {
            // The field read that defines the value checked by the guard must be used only for the
            // guard or directly if the field's value was not the default value
            val ifStmt = code(result._1).asIf

            val expr =
                if (ifStmt.leftExpr.isConst) ifStmt.rightExpr
                else ifStmt.leftExpr

            val definitions = expr.asVar.definedBy
            if (definitions.forall(_ >= 0)) {

                fieldReadIndex = definitions.head

                val fieldReadUses = code(definitions.head).asAssignment.targetVar.usedBy

                val fieldReadUsedCorrectly =
                    fieldReadUses.forall(use => use == result._1 || use == result._3)

                if (definitions.size == 1 && definitions.head >= 0 && fieldReadUsedCorrectly) {
                    // Found proper guard
                    finalResult = (fieldReadIndex, result._1, result._2, result._3) :: finalResult
                }
            }
        })
        finalResult
    }

    /**
     * Returns all predecessor BasicBlocks of a CFGNode.
     */
    def getPredecessors(node: CFGNode, visited: Set[CFGNode]): List[BasicBlock] = {
        def getPredecessorsInternal(node: CFGNode, visited: Set[CFGNode]): Iterator[BasicBlock] = {
            node.predecessors.iterator.flatMap { currentNode =>
                if (currentNode.isBasicBlock) {
                    if (visited.contains(currentNode))
                        None
                    else
                        Some(currentNode.asBasicBlock)
                } else
                    getPredecessorsInternal(currentNode, visited)
            }
        }
        getPredecessorsInternal(node, visited).toList
    }

    /**
     * Determines whether a node is a transitive predecessor of another node.
     */
    def isTransitivePredecessor(possiblePredecessor: CFGNode, node: CFGNode): Boolean = {

        val visited: mutable.Set[CFGNode] = mutable.Set.empty

        def isTransitivePredecessorInternal(possiblePredecessor: CFGNode, node: CFGNode): Boolean = {
            if (possiblePredecessor == node)
                true
            else if (visited.contains(node))
                false
            else {
                visited += node
                node.predecessors.exists(
                    currentNode => isTransitivePredecessorInternal(possiblePredecessor, currentNode)
                )
            }
        }
        isTransitivePredecessorInternal(possiblePredecessor, node)
    }

    /**
     * Returns all successors BasicBlocks of a CFGNode
     */
    def getSuccessors(node: CFGNode, visited: Set[CFGNode]): List[BasicBlock] = {
        def getSuccessorsInternal(node: CFGNode, visited: Set[CFGNode]): Iterator[BasicBlock] = {
            node.successors.iterator flatMap { currentNode =>
                if (currentNode.isBasicBlock)
                    if (visited.contains(currentNode)) None
                    else Some(currentNode.asBasicBlock)
                else getSuccessors(currentNode, visited)
            }
        }
        getSuccessorsInternal(node, visited).toList
    }

    /**
     * Checks if an expression is a field read of the currently analyzed field.
     * For instance fields, the read must be on the `this` reference.
     */
    def isReadOfCurrentField(
        expr:    Expr[V],
        tacCode: TACode[TACMethodParameter, V],
        index:   Int
    )(implicit state: State): Boolean = {
        def isExprReadOfCurrentField: Int => Boolean =
            exprIndex =>
                exprIndex == index ||
                    exprIndex >= 0 && isReadOfCurrentField(
                        tacCode.stmts(exprIndex).asAssignment.expr,
                        tacCode,
                        exprIndex
                    )
        (expr.astID: @switch) match {
            case GetField.ASTID =>
                val objRefDefinition = expr.asGetField.objRef.asVar.definedBy
                if (objRefDefinition != SelfReferenceParameter) false
                else expr.asGetField.resolveField(project).contains(state.field)

            case GetStatic.ASTID             => expr.asGetStatic.resolveField(project).contains(state.field)
            case PrimitiveTypecastExpr.ASTID => false

            case Compare.ASTID =>
                val leftExpr = expr.asCompare.left
                val rightExpr = expr.asCompare.right
                leftExpr.asVar.definedBy
                    .forall(index => index >= 0 && tacCode.stmts(index).asAssignment.expr.isConst) &&
                    rightExpr.asVar.definedBy.forall(isExprReadOfCurrentField) ||
                    rightExpr.asVar.definedBy
                    .forall(index => index >= 0 && tacCode.stmts(index).asAssignment.expr.isConst) &&
                    leftExpr.asVar.definedBy.forall(isExprReadOfCurrentField)

            case VirtualFunctionCall.ASTID =>
                val functionCall = expr.asVirtualFunctionCall
                val fieldType = state.field.fieldType
                functionCall.params.isEmpty && (
                    fieldType match {
                        case ObjectType.Byte    => functionCall.name == "byteValue"
                        case ObjectType.Short   => functionCall.name == "shortValue"
                        case ObjectType.Integer => functionCall.name == "intValue"
                        case ObjectType.Long    => functionCall.name == "longValue"
                        case ObjectType.Float   => functionCall.name == "floatValue"
                        case ObjectType.Double  => functionCall.name == "doubleValue"
                        case _                  => false
                    }
                ) && functionCall.receiver.asVar.definedBy.forall(isExprReadOfCurrentField)

            case _ => false
        }
    }

    /**
     * Determines if an if-Statement is actually a guard for the current field, i.e. it compares
     * the current field to the default value.
     */
    def isGuard(
        ifStmt:        If[V],
        defaultValues: Set[Any],
        code:          Array[Stmt[V]],
        tacCode:       TACode[TACMethodParameter, V]
    )(implicit state: State): Boolean = {

        def isDefaultConst(expr: Expr[V]): Boolean = {

            if (expr.isVar) {
                val defSites = expr.asVar.definedBy
                defSites.size == 1 && defSites.forall(_ >= 0) &&
                    defSites.forall(defSite => isDefaultConst(code(defSite).asAssignment.expr))
            } else {
                // default value check
                expr.isIntConst && defaultValues.contains(expr.asIntConst.value) ||
                    expr.isFloatConst && defaultValues.contains(expr.asFloatConst.value) ||
                    expr.isDoubleConst && defaultValues.contains(expr.asDoubleConst.value) ||
                    expr.isLongConst && defaultValues.contains(expr.asLongConst.value) ||
                    expr.isStringConst && defaultValues.contains(expr.asStringConst.value) ||
                    expr.isNullExpr && defaultValues.contains(null)
            }
        }

        /**
         * Checks whether the non-constant expression of the if-Statement is a read of the current
         * field.
         */
        def isGuardInternal(
            expr:    V,
            tacCode: TACode[TACMethodParameter, V]
        ): Boolean = {
            expr.definedBy forall { index =>
                if (index < 0)
                    false // If the value is from a parameter, this can not be the guard
                else {
                    val expression = code(index).asAssignment.expr
                    //in case of Integer etc.... .initValue()
                    if (expression.isVirtualFunctionCall) {
                        val virtualFunctionCall = expression.asVirtualFunctionCall
                        virtualFunctionCall.receiver.asVar.definedBy.forall(
                            receiverDefSite =>
                                receiverDefSite >= 0 &&
                                    isReadOfCurrentField(code(receiverDefSite).asAssignment.expr, tacCode, index)
                        )
                    } else
                        isReadOfCurrentField(expression, tacCode, index)
                }
            }
        }

        //Special handling for these types needed because of compare function in bytecode
        def hasFloatDoubleOrLongType(fieldType: FieldType): Boolean =
            fieldType.isFloatType || fieldType.isDoubleType || fieldType.isLongType

        if (ifStmt.rightExpr.isVar && hasFloatDoubleOrLongType(state.field.fieldType) &&
            ifStmt.rightExpr.asVar.definedBy.head > 0 &&
            tacCode.stmts(ifStmt.rightExpr.asVar.definedBy.head).asAssignment.expr.isCompare) {

            val left =
                tacCode.stmts(ifStmt.rightExpr.asVar.definedBy.head).asAssignment.expr.asCompare.left.asVar
            val right =
                tacCode.stmts(ifStmt.rightExpr.asVar.definedBy.head).asAssignment.expr.asCompare.right.asVar
            val leftExpr = tacCode.stmts(left.definedBy.head).asAssignment.expr
            val rightExpr = tacCode.stmts(right.definedBy.head).asAssignment.expr

            if (leftExpr.isGetField || leftExpr.isGetStatic) isDefaultConst(rightExpr)
            else (rightExpr.isGetField || rightExpr.isGetStatic) && isDefaultConst(leftExpr)

        } else if (ifStmt.leftExpr.isVar && ifStmt.rightExpr.isVar && ifStmt.leftExpr.asVar.definedBy.head >= 0 &&
            ifStmt.rightExpr.asVar.definedBy.head >= 0 &&
            hasFloatDoubleOrLongType(state.field.fieldType) && tacCode
            .stmts(ifStmt.leftExpr.asVar.definedBy.head)
            .asAssignment
            .expr
            .isCompare &&
            ifStmt.leftExpr.isVar && ifStmt.rightExpr.isVar) {

            val left =
                tacCode.stmts(ifStmt.leftExpr.asVar.definedBy.head).asAssignment.expr.asCompare.left.asVar
            val right =
                tacCode.stmts(ifStmt.leftExpr.asVar.definedBy.head).asAssignment.expr.asCompare.right.asVar
            val leftExpr = tacCode.stmts(left.definedBy.head).asAssignment.expr
            val rightExpr = tacCode.stmts(right.definedBy.head).asAssignment.expr

            if (leftExpr.isGetField || leftExpr.isGetStatic) isDefaultConst(rightExpr)
            else (rightExpr.isGetField || rightExpr.isGetStatic) && isDefaultConst(leftExpr)

        } else if (ifStmt.rightExpr.isVar && isDefaultConst(ifStmt.leftExpr)) {
            isGuardInternal(ifStmt.rightExpr.asVar, tacCode)
        } else if (ifStmt.leftExpr.isVar && isDefaultConst(ifStmt.rightExpr)) {
            isGuardInternal(ifStmt.leftExpr.asVar, tacCode)
        } else false
    }

    /**
     * Checks that the returned value is definitely read from the field.
     */
    def isFieldValueReturned(
        write:        FieldWriteAccessStmt[V],
        writeIndex:   Int,
        readIndex:    Int,
        taCode:       TACode[TACMethodParameter, V],
        guardIndexes: List[(Int, Int, Int, Int)]
    )(implicit state: State): Boolean = {

        def isSimpleReadOfField(expr: Expr[V]) =
            expr.astID match {

                case GetField.ASTID =>
                    val objRefDefinition = expr.asGetField.objRef.asVar.definedBy
                    if (objRefDefinition != SelfReferenceParameter)
                        false
                    else
                        expr.asGetField.resolveField(project).contains(state.field)

                case GetStatic.ASTID => expr.asGetStatic.resolveField(project).contains(state.field)

                case _               => false
            }

        taCode.stmts.forall { stmt =>
            !stmt.isReturnValue || {

                val returnValueDefs = stmt.asReturnValue.expr.asVar.definedBy
                val assignedValueDefSite = write.value.asVar.definedBy
                returnValueDefs.forall(_ >= 0) && {
                    if (returnValueDefs.size == 1 && returnValueDefs.head != readIndex) {
                        val expr = taCode.stmts(returnValueDefs.head).asAssignment.expr
                        isSimpleReadOfField(expr) && guardIndexes.exists {
                            case (_, guardIndex, defaultCase, _) =>
                                dominates(guardIndex, returnValueDefs.head, taCode) &&
                                    (!dominates(defaultCase, returnValueDefs.head, taCode) ||
                                        dominates(writeIndex, returnValueDefs.head, taCode))
                        }
                    } //The field is either read before the guard and returned or
                    // the value assigned to the field is returned
                    else {
                        returnValueDefs.size == 2 && assignedValueDefSite.size == 1 &&
                            returnValueDefs.contains(readIndex) && {
                                returnValueDefs.contains(assignedValueDefSite.head) || {
                                    val potentiallyReadIndex = returnValueDefs.filter(_ != readIndex).head
                                    val expr = taCode.stmts(potentiallyReadIndex).asAssignment.expr
                                    isSimpleReadOfField(expr) &&
                                        guardIndexes.exists {
                                            case (_, guardIndex, defaultCase, _) =>
                                                dominates(guardIndex, potentiallyReadIndex, taCode) &&
                                                    (!dominates(defaultCase, returnValueDefs.head, taCode) ||
                                                        dominates(writeIndex, returnValueDefs.head, taCode))
                                        }
                                }
                            }
                    }
                }
            }
        }
    }

}

trait AbstractL2FieldAssignabilityAnalysisScheduler extends AbstractFieldAssignabilityAnalysisScheduler {
    override def uses: Set[PropertyBounds] = super.uses ++ PropertyBounds.ubs(
        FieldReadAccessInformation,
        FieldWriteAccessInformation
    )
}

/**
 * Executor for the eager field assignability analysis.
 */
object EagerL2FieldAssignabilityAnalysis
    extends AbstractL2FieldAssignabilityAnalysisScheduler
    with BasicFPCFEagerAnalysisScheduler {

    override def derivesEagerly: Set[PropertyBounds] = Set(derivedProperty)

    override def derivesCollaboratively: Set[PropertyBounds] = Set.empty

    final override def start(p: SomeProject, ps: PropertyStore, unused: Null): FPCFAnalysis = {
        val analysis = new L2FieldAssignabilityAnalysis(p)
        val fields = p.allFields
        ps.scheduleEagerComputationsForEntities(fields)(analysis.determineFieldAssignability)
        analysis
    }
}

/**
 * Executor for the lazy field assignability analysis.
 */
object LazyL2FieldAssignabilityAnalysis
    extends AbstractFieldAssignabilityAnalysisScheduler
    with BasicFPCFLazyAnalysisScheduler {

    override def derivesLazily: Some[PropertyBounds] = Some(derivedProperty)

    final override def register(
        p:      SomeProject,
        ps:     PropertyStore,
        unused: Null
    ): FPCFAnalysis = {
        val analysis = new L2FieldAssignabilityAnalysis(p)
        ps.registerLazyPropertyComputation(
            FieldAssignability.key,
            analysis.doDetermineFieldAssignability
        )
        analysis
    }
}
