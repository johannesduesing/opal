/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj
package br
package fpcf
package properties
package fieldaccess

import org.opalj.br.DeclaredField
import org.opalj.br.Method
import org.opalj.br.PCs
import org.opalj.br.fpcf.properties.Context
import org.opalj.collection.immutable.IntTrieSet
import org.opalj.fpcf.EPK
import org.opalj.fpcf.InterimEUBP
import org.opalj.fpcf.InterimUBP
import org.opalj.fpcf.PartialResult
import org.opalj.fpcf.Property
import org.opalj.fpcf.PropertyKey
import org.opalj.value.ValueInformation

import scala.collection.immutable.IntMap

/**
 * A convenience class for field access collection. Manages direct, indirect and incomplete
 * field access sites and allows the analyses to retrieve the required [[org.opalj.fpcf.PartialResult]]s for
 * [[FieldReadAccessInformation]], [[FieldWriteAccessInformation]], [[MethodFieldReadAccessInformation]] and
 * [[MethodFieldWriteAccessInformation]].
 *
 * @author Maximilian Rüsch
 */
sealed trait FieldAccesses {
    final def partialResults(accessContext: Context): IterableOnce[PartialResult[_, _ >: Null <: Property]] =
        if (containsNoMethodBasedAccessInformation)
            partialResultsForFieldBasedFieldAccesses
        else
            Iterator(
                partialResultForReadFields(accessContext),
                partialResultForWriteFields(accessContext)
            ) ++ partialResultsForFieldBasedFieldAccesses

    private[this] def containsNoMethodBasedAccessInformation =
        directAccessedFields.isEmpty && indirectAccessedFields.isEmpty && incompleteAccessSites.isEmpty

    protected def directAccessedFields: IntMap[IntTrieSet] = IntMap.empty
    protected def directReadReceivers: IntMap[IntMap[AccessReceiver]] = IntMap.empty
    protected def directWriteReceivers: IntMap[IntMap[AccessReceiver]] = IntMap.empty
    protected def directWriteParameters: IntMap[IntMap[AccessParameter]] = IntMap.empty

    protected def indirectAccessedFields: IntMap[IntTrieSet] = IntMap.empty
    protected def indirectReadReceivers: IntMap[IntMap[AccessReceiver]] = IntMap.empty
    protected def indirectWriteReceivers: IntMap[IntMap[AccessReceiver]] = IntMap.empty
    protected def indirectWriteParameters: IntMap[IntMap[AccessParameter]] = IntMap.empty

    protected def incompleteAccessSites: PCs = IntTrieSet.empty

    private[this] def partialResultForAccessedFields[S >: Null <: MethodFieldAccessInformation[S]](
        accessContext:             Context,
        propertyKey:               PropertyKey[S],
        noFieldAccessesValue:      S,
        fieldAccessesValueUpdater: S => S
    ): PartialResult[Method, S] = {
        val method = accessContext.method.definedMethod

        PartialResult[Method, S](method, propertyKey, {
            case InterimUBP(_) if containsNoMethodBasedAccessInformation =>
                None

            case InterimUBP(ub) =>
                Some(InterimEUBP(method, fieldAccessesValueUpdater(ub)))

            case _: EPK[_, _] if containsNoMethodBasedAccessInformation =>
                Some(InterimEUBP(method, noFieldAccessesValue))

            case _: EPK[_, _] =>
                Some(InterimEUBP(method, fieldAccessesValueUpdater(noFieldAccessesValue)))

            case r =>
                throw new IllegalStateException(s"unexpected previous result $r")
        })
    }

    private[this] def partialResultForReadFields(
        accessContext: Context
    ): PartialResult[Method, MethodFieldReadAccessInformation] = {
        partialResultForAccessedFields(
            accessContext,
            MethodFieldReadAccessInformation.key,
            NoMethodFieldReadAccessInformation,
            previousFRA => previousFRA.updateWithFieldAccesses(
                accessContext,
                incompleteAccessSites,
                directReadReceivers,
                indirectReadReceivers
            )
        )
    }

    private[this] def partialResultForWriteFields(
        accessContext: Context
    ): PartialResult[Method, MethodFieldWriteAccessInformation] = {
        partialResultForAccessedFields(
            accessContext,
            MethodFieldWriteAccessInformation.key,
            NoMethodFieldWriteAccessInformation,
            previousFWA => previousFWA.updateWithFieldAccesses(
                accessContext,
                incompleteAccessSites,
                directWriteReceivers,
                directWriteParameters,
                indirectWriteReceivers,
                indirectWriteParameters
            )
        )
    }

    protected def partialResultsForFieldBasedFieldAccesses: IterableOnce[PartialResult[DeclaredField, _ >: Null <: FieldAccessInformation[_]]] =
        Iterator.empty
}

trait IncompleteFieldAccesses extends FieldAccesses {
    private[this] var _incompleteAccessSites = IntTrieSet.empty
    override protected def incompleteAccessSites: IntTrieSet = _incompleteAccessSites

    def addIncompleteAccessSite(pc: Int): Unit = _incompleteAccessSites += pc
}

trait CompleteFieldAccesses extends FieldAccesses {

    private def createFieldPartialResultForContext[S <: FieldAccessInformation[S]](
        field:       DeclaredField,
        propertyKey: PropertyKey[S],
        property:    S
    ): PartialResult[DeclaredField, _ >: Null <: FieldAccessInformation[_]] = {
        PartialResult[DeclaredField, FieldAccessInformation[S]](field, propertyKey, {
            case InterimUBP(ub) =>
                Some(InterimEUBP(field, ub.included(property)))

            case _: EPK[_, _] =>
                Some(InterimEUBP(field, property))

            case r =>
                throw new IllegalStateException(s"unexpected previous result $r")
        })
    }

    @inline protected[this] def pcFieldMapNestedUpdate[T](
        nestedMap: IntMap[IntMap[T]],
        pc:        Int,
        fieldId:   Int,
        value:     T
    ): IntMap[IntMap[T]] = {
        nestedMap.updated(pc, nestedMap.getOrElse(pc, IntMap.empty).updated(fieldId, value))
    }

    protected var _accessedFields: IntMap[IntTrieSet] = IntMap.empty
    protected var _readReceivers: IntMap[IntMap[AccessReceiver]] = IntMap.empty
    protected var _writeReceivers: IntMap[IntMap[AccessReceiver]] = IntMap.empty
    protected var _writeParameters: IntMap[IntMap[AccessParameter]] = IntMap.empty

    private[this] var _partialResultsForFieldBasedFieldAccesses: List[PartialResult[DeclaredField, _ >: Null <: Property with FieldAccessInformation[_]]] =
        List.empty

    protected def addFieldAccess[S <: FieldAccessInformation[S]](
        pc:              Int,
        field:           DeclaredField,
        propertyKey:     PropertyKey[S],
        propertyFactory: () => S
    ): Unit = {
        val oldFieldsAtPCOpt = _accessedFields.get(pc)
        if (oldFieldsAtPCOpt.isEmpty) {
            _accessedFields = _accessedFields.updated(pc, IntTrieSet(field.id))
            _partialResultsForFieldBasedFieldAccesses ::= createFieldPartialResultForContext(
                field,
                propertyKey,
                propertyFactory()
            )
        } else {
            val oldFieldsAtPC = oldFieldsAtPCOpt.get
            val newFieldsAtPC = oldFieldsAtPC + field.id

            // here we assert that IntSet returns the identity if the element is already contained
            if (newFieldsAtPC ne oldFieldsAtPC) {
                _accessedFields = _accessedFields.updated(pc, newFieldsAtPC)
                _partialResultsForFieldBasedFieldAccesses ::= createFieldPartialResultForContext(
                    field,
                    propertyKey,
                    propertyFactory()
                )
            }
        }
    }

    override protected def partialResultsForFieldBasedFieldAccesses: IterableOnce[PartialResult[DeclaredField, _ >: Null <: FieldAccessInformation[_]]] =
        _partialResultsForFieldBasedFieldAccesses.iterator ++ super.partialResultsForFieldBasedFieldAccesses
}

trait DirectFieldAccessesBase extends CompleteFieldAccesses {

    override protected def directAccessedFields: IntMap[IntTrieSet] = _accessedFields
    override protected def directReadReceivers: IntMap[IntMap[AccessReceiver]] = _readReceivers
    override protected def directWriteReceivers: IntMap[IntMap[AccessReceiver]] = _writeReceivers
    override protected def directWriteParameters: IntMap[IntMap[AccessParameter]] = _writeParameters

    def addFieldRead(accessContext: Context, pc: Int, field: DeclaredField, receiver: AccessReceiver): Unit = {
        addFieldAccess(pc, field, FieldReadAccessInformation.key,
            () => FieldReadAccessInformation(IntMap((accessContext.id, IntMap((pc, receiver))))))

        _readReceivers = pcFieldMapNestedUpdate(_readReceivers, pc, field.id, receiver)
    }

    def addFieldWrite(accessContext: Context, pc: Int, field: DeclaredField, receiver: AccessReceiver, param: AccessParameter): Unit = {
        addFieldAccess(pc, field, FieldWriteAccessInformation.key,
            () => FieldWriteAccessInformation(
                IntMap((accessContext.id, IntMap((pc, receiver)))),
                IntMap((accessContext.id, IntMap((pc, param))))
            ))
        _writeReceivers = pcFieldMapNestedUpdate(_writeReceivers, pc, field.id, receiver)
        _writeParameters = pcFieldMapNestedUpdate(_writeParameters, pc, field.id, param)
    }
}

trait IndirectFieldAccessesBase extends CompleteFieldAccesses {

    override protected def indirectAccessedFields: IntMap[IntTrieSet] = _accessedFields
    override protected def indirectReadReceivers: IntMap[IntMap[AccessReceiver]] = _readReceivers
    override protected def indirectWriteReceivers: IntMap[IntMap[AccessReceiver]] = _writeReceivers
    override protected def indirectWriteParameters: IntMap[IntMap[AccessParameter]] = _writeParameters

    def addFieldRead(
        accessContext: Context,
        pc:            Int,
        field:         DeclaredField,
        receiver:      Option[(ValueInformation, IntTrieSet)]
    ): Unit = {
        addFieldAccess(pc, field, FieldReadAccessInformation.key,
            () => FieldReadAccessInformation(
                IntMap.empty,
                IntMap((accessContext.id, IntMap((pc, receiver))))
            ))
        _readReceivers = pcFieldMapNestedUpdate(_readReceivers, pc, field.id, receiver)
    }

    def addFieldWrite(
        accessContext: Context,
        pc:            Int,
        field:         DeclaredField,
        receiver:      Option[(ValueInformation, IntTrieSet)],
        param:         Option[(ValueInformation, IntTrieSet)]
    ): Unit = {
        addFieldAccess(pc, field, FieldWriteAccessInformation.key,
            () => FieldWriteAccessInformation(
                IntMap.empty,
                IntMap.empty,
                IntMap((accessContext.id, IntMap((pc, receiver)))),
                IntMap((accessContext.id, IntMap((pc, param))))
            ))
        _writeReceivers = pcFieldMapNestedUpdate(_writeReceivers, pc, field.id, receiver)
        _writeParameters = pcFieldMapNestedUpdate(_writeParameters, pc, field.id, param)
    }
}

class DirectFieldAccesses extends DirectFieldAccessesBase with IncompleteFieldAccesses
class IndirectFieldAccesses extends IndirectFieldAccessesBase with IncompleteFieldAccesses
