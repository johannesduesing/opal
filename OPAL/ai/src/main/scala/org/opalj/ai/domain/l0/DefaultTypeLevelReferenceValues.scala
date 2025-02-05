/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj
package ai
package domain
package l0

import org.opalj.br.ArrayType
import org.opalj.br.ObjectType
import org.opalj.br.ReferenceType
import org.opalj.collection.immutable.UIDSet
import org.opalj.collection.immutable.UIDSet1
import org.opalj.value.IsMObjectValue
import org.opalj.value.IsPrimitiveValue
import org.opalj.value.IsSArrayValue
import org.opalj.value.IsSObjectValue
import org.opalj.value.TypeOfReferenceValue

/**
 * Default implementation for handling reference values.
 *
 * @author Michael Eichberg
 */
trait DefaultTypeLevelReferenceValues
    extends DefaultSpecialDomainValuesBinding
    with TypeLevelReferenceValues {
    domain: IntegerValuesDomain with TypedValuesFactory with Configuration =>

    // -----------------------------------------------------------------------------------
    //
    // REPRESENTATION OF REFERENCE VALUES
    //
    // -----------------------------------------------------------------------------------

    type DomainNullValue <: ANullValue with AReferenceValue
    type DomainObjectValue <: AnObjectValue with AReferenceValue // <= SObject.. and MObject...
    type DomainArrayValue <: AnArrayValue with AReferenceValue

    protected[this] class ANullValue() extends super.NullValueLike { this: DomainNullValue =>

        override protected def doJoin(pc: Int, other: DomainValue): Update[DomainValue] = {
            other match {
                case _: ANullValue         => NoUpdate
                case _: ReferenceValueLike =>
                    // THIS domain does not track whether ReferenceValues
                    // are definitively not null!
                    StructuralUpdate(other)
            }
        }

        override def abstractsOver(other: DomainValue): Boolean = {
            other.isInstanceOf[ANullValue]
        }
    }

    protected[this] trait AnArrayValue
        extends super.ArrayValueLike
        with IsSArrayValue
        with SReferenceValue[ArrayType] {
        this: DomainArrayValue =>

        override def isAssignable(value: DomainValue): Answer = {
            value match {

                case IsPrimitiveValue(primitiveType) =>
                    // The following is an over approximation that makes it theoretically
                    // possible to store an int value in a byte array. However,
                    // such bytecode is illegal
                    Answer(
                        theUpperTypeBound.componentType.computationalType eq
                            primitiveType.computationalType
                    )

                case elementValue @ TypeOfReferenceValue(EmptyUpperTypeBound) =>
                    // the elementValue is "null"
                    assert(elementValue.isNull.isYes)
                    // e.g., it is possible to store null in the n-1 dimensions of
                    // a n-dimensional array of primitive values
                    if (theUpperTypeBound.componentType.isReferenceType)
                        Yes
                    else
                        No

                case elementValue @ TypeOfReferenceValue(UIDSet1(elementValueType)) =>
                    classHierarchy.canBeStoredIn(
                        elementValueType,
                        elementValue.isPrecise,
                        this.theUpperTypeBound,
                        this.isPrecise
                    )

                case elementValue @ TypeOfReferenceValue(otherUpperTypeBound) =>
                    val elementValueIsPrecise = elementValue.isPrecise
                    val thisArrayType = this.theUpperTypeBound
                    val thisIsPrecise = this.isPrecise
                    var finalAnswer: Answer = No
                    otherUpperTypeBound.exists { elementValueType =>
                        classHierarchy.canBeStoredIn(
                            elementValueType,
                            elementValueIsPrecise,
                            thisArrayType,
                            thisIsPrecise
                        ) match {
                            case Yes =>
                                return Yes;

                            case intermediateAnswer =>
                                finalAnswer = finalAnswer join intermediateAnswer
                                false
                        }
                    }
                    finalAnswer
            }
        }

        override protected def doLoad(
            pc:                  Int,
            index:               DomainValue,
            potentialExceptions: ExceptionValues
        ): ArrayLoadResult = {
            val value = TypedValue(pc, theUpperTypeBound.componentType)
            ComputedValueOrException(value, potentialExceptions)
        }

        override protected def doStore(
            pc:               Int,
            value:            DomainValue,
            index:            DomainValue,
            thrownExceptions: ExceptionValues
        ): ArrayStoreResult = {
            ComputationWithSideEffectOrException(thrownExceptions)
        }

        // WIDENING OPERATION
        override protected def doJoin(joinPC: Int, other: DomainValue): Update[DomainValue] = {
            val thisUTB = this.theUpperTypeBound
            other match {

                case otherObj@SObjectValueLike(thatUpperTypeBound) =>
                    if(abstractsNullness(this, otherObj)){
                        classHierarchy.joinAnyArrayTypeWithObjectType(thatUpperTypeBound) match {
                            case UIDSet1(newUpperTypeBound) =>
                                if (newUpperTypeBound eq `thatUpperTypeBound`)
                                    StructuralUpdate(other)
                                else
                                    StructuralUpdate(ReferenceValue(joinPC, newUpperTypeBound))
                            case newUpperTypeBound =>
                                StructuralUpdate(ObjectValue(joinPC, newUpperTypeBound))
                        }
                    } else {
                        // If the two objects are not null-compatible, we need to update regardless of type compatibility
                        StructuralUpdate(ObjectValue(joinPC, classHierarchy.joinAnyArrayTypeWithObjectType(thatUpperTypeBound) ))
                    }

                case otherObj@MObjectValueLike(thatUpperTypeBound) =>
                    if(abstractsNullness(this, otherObj)){
                        classHierarchy.joinAnyArrayTypeWithMultipleTypesBound(thatUpperTypeBound) match {
                            case `thatUpperTypeBound` =>
                                StructuralUpdate(other)
                            case UIDSet1(newUpperTypeBound) =>
                                StructuralUpdate(ReferenceValue(joinPC, newUpperTypeBound))
                            case newUpperTypeBound =>
                                // this case should not occur...
                                StructuralUpdate(ObjectValue(joinPC, newUpperTypeBound))
                        }
                    } else {
                        StructuralUpdate(ObjectValue(joinPC, classHierarchy.joinAnyArrayTypeWithMultipleTypesBound(thatUpperTypeBound)))
                    }


                case otherObj@AnArrayValue(thatUpperTypeBound) =>
                    if(abstractsNullness(this, otherObj)){
                        classHierarchy.joinArrayTypes(thisUTB, thatUpperTypeBound) match {
                            case Left(`thisUTB`) =>
                                NoUpdate
                            case Left(`thatUpperTypeBound`) =>
                                StructuralUpdate(other)
                            case Left(newUpperTypeBound) =>
                                StructuralUpdate(ArrayValue(joinPC, newUpperTypeBound))
                            case Right(newUpperTypeBound) =>
                                StructuralUpdate(ObjectValue(joinPC, newUpperTypeBound))
                        }
                    } else {
                        // If the two objects are not null-compatible, we need to update regardless of type compatibility
                        classHierarchy.joinArrayTypes(thisUTB, thatUpperTypeBound) match {
                            case Left(typeBound) =>
                                StructuralUpdate(ArrayValue(joinPC, typeBound))
                            case Right(typeBound) =>
                                StructuralUpdate(ObjectValue(joinPC, typeBound))
                        }
                    }


                case _: ANullValue =>
                    if(this.isNull.isNo) {
                        // Must make sure new object value is nullable!
                        StructuralUpdate(ArrayValue(joinPC, thisUTB))
                    } else
                        NoUpdate
            }
        }

        override def abstractsOver(other: DomainValue): Boolean = {
            other match {
                case _: ANullValue => true
                case AnArrayValue(thatUpperTypeBound) =>
                    domain.isSubtypeOf(thatUpperTypeBound, this.theUpperTypeBound)
                case _ => false
            }
        }

        override def adapt(target: TargetDomain, origin: ValueOrigin): target.DomainValue = {
            target.ReferenceValue(origin, theUpperTypeBound)
        }
    }

    /**
     * Enables matching of `DomainValue`s that are array values.
     */
    object AnArrayValue {
        def unapply(value: AnArrayValue): Some[ArrayType] = Some(value.theUpperTypeBound)
    }

    protected trait AnObjectValue extends super.ObjectValueLike { this: DomainObjectValue =>

        protected def asStructuralUpdate(
            pc:                Int,
            newUpperTypeBound: UIDSet[ObjectType]
        ): Update[DomainValue] = {
            if (newUpperTypeBound.isSingletonSet)
                StructuralUpdate(ObjectValue(pc, newUpperTypeBound.head))
            else
                StructuralUpdate(ObjectValue(pc, newUpperTypeBound))
        }

        override final def length(pc: Int): Computation[DomainValue, ExceptionValue] = {
            throw DomainException("arraylength not possible; this is not an array value: " + this)
        }

        override final def load(pc: Int, index: DomainValue): ArrayLoadResult = {
            throw DomainException("arrayload not possible; this is not an array value: " + this)
        }

        override final def store(
            pc:    Int,
            value: DomainValue,
            index: DomainValue
        ): ArrayStoreResult = {
            throw DomainException("arraystore not possible; this is not an array value: " + this)
        }

    }

    private def abstractsNullness(thisObj: ReferenceValueLike, otherObj: ReferenceValueLike): Boolean = {
        if(thisObj.isNull eq otherObj.isNull) true
        else if(thisObj.isNull.isNo && otherObj.isNull.isNotNo) false
        else if(thisObj.isNull.isYes && otherObj.isNull.isNoOrUnknown) false
        else true
    }

    protected trait SObjectValueLike
        extends AnObjectValue
        with SReferenceValue[ObjectType]
        with IsSObjectValue { this: DomainObjectValue =>

        // WIDENING OPERATION
        override protected def doJoin(pc: Int, other: DomainValue): Update[DomainValue] = {
            val thisUpperTypeBound = this.theUpperTypeBound
            other match {

                case otherObj@SObjectValueLike(thatUpperTypeBound) =>
                    if(abstractsNullness(this, otherObj)){
                        classHierarchy.joinObjectTypes(thisUpperTypeBound, thatUpperTypeBound, true) match {
                            case UIDSet1(newUpperTypeBound) =>
                                if (newUpperTypeBound eq `thisUpperTypeBound`)
                                    NoUpdate
                                else if (newUpperTypeBound eq `thatUpperTypeBound`)
                                    StructuralUpdate(other)
                                else
                                    StructuralUpdate(ObjectValue(pc, newUpperTypeBound))

                            case newUpperTypeBound =>
                                StructuralUpdate(ObjectValue(pc, newUpperTypeBound))
                        }
                    } else {
                        // If the two objects are not null-compatible, we need to update regardless of type compatibility
                        asStructuralUpdate(pc, classHierarchy.joinObjectTypes(thisUpperTypeBound, thatUpperTypeBound, true))
                    }

                case otherObj@MObjectValueLike(thatUpperTypeBound) =>
                    if(abstractsNullness(this, otherObj)){
                        classHierarchy.joinObjectTypes(thisUpperTypeBound, thatUpperTypeBound, true) match {
                            case `thatUpperTypeBound` =>
                                    StructuralUpdate(other)
                            case UIDSet1(`thisUpperTypeBound`) =>
                                NoUpdate
                            case newUpperTypeBound =>
                                asStructuralUpdate(pc, newUpperTypeBound)
                        }
                    } else {
                        // If the two objects are not null-compatible, we need to update regardless of type compatibility
                        asStructuralUpdate(pc, classHierarchy.joinObjectTypes(thisUpperTypeBound, thatUpperTypeBound, true))
                    }

                case otherObj: AnArrayValue =>
                    if(abstractsNullness(this, otherObj)){
                        classHierarchy.joinAnyArrayTypeWithObjectType(thisUpperTypeBound) match {
                            case UIDSet1(newUpperTypeBound) =>
                                if (newUpperTypeBound eq `thisUpperTypeBound`)
                                    NoUpdate
                                else
                                    StructuralUpdate(ObjectValue(pc, newUpperTypeBound))
                            case newUpperTypeBound =>
                                StructuralUpdate(ObjectValue(pc, newUpperTypeBound))
                        }
                    } else {
                        // If the two objects are not null-compatible, we need to update regardless of type compatibility
                        asStructuralUpdate(pc, classHierarchy.joinAnyArrayTypeWithObjectType(thisUpperTypeBound))
                    }


                case _: ANullValue =>
                    if(this.isNull.isNo) {
                        // Must make sure new object value is nullable!
                        StructuralUpdate(ObjectValue(pc, thisUpperTypeBound))
                    } else
                        NoUpdate
            }
        }

        override def abstractsOver(other: DomainValue): Boolean = {
            other match {
                case otherObj@SObjectValueLike(thatUpperTypeBound) =>
                    domain.isSubtypeOf(thatUpperTypeBound, this.theUpperTypeBound) && abstractsNullness(this, otherObj)

                case otherObj@AnArrayValue(thatUpperTypeBound) =>
                    domain.isSubtypeOf(thatUpperTypeBound, this.theUpperTypeBound) && abstractsNullness(this, otherObj)

                case otherObj@MObjectValueLike(thatUpperTypeBound) =>
                    classHierarchy.isSubtypeOf(
                        thatUpperTypeBound.asInstanceOf[UIDSet[ReferenceType]],
                        this.theUpperTypeBound
                    ) && abstractsNullness(this, otherObj)

                case _: ANullValue => this.isNull.isNotNo
            }
        }

        override def adapt(target: TargetDomain, origin: ValueOrigin): target.DomainValue = {
            target.ReferenceValue(origin, theUpperTypeBound)
        }

    }

    object SObjectValueLike {
        def unapply(that: SObjectValueLike): Some[ObjectType] = Some(that.theUpperTypeBound)
    }

    protected trait MObjectValueLike extends AnObjectValue with IsMObjectValue {
        value: DomainObjectValue =>

        override def leastUpperType: Option[ReferenceType] = {
            Some(classHierarchy.joinObjectTypesUntilSingleUpperBound(upperTypeBound))
        }

        override protected def doJoin(pc: Int, other: DomainValue): Update[DomainValue] = {
            val thisUTB = this.upperTypeBound
            other match {

                case otherObj@SObjectValueLike(thatUTB) =>
                    if(abstractsNullness(this, otherObj)){
                        classHierarchy.joinObjectTypes(thatUTB, thisUTB, true) match {
                            case `thisUTB`          => NoUpdate
                            case UIDSet1(`thatUTB`) => StructuralUpdate(other)
                            case newUTB             => asStructuralUpdate(pc, newUTB)
                        }
                    } else {
                        // If we do not cover the nullness of the other obj, we need to update
                        asStructuralUpdate(pc, classHierarchy.joinObjectTypes(thatUTB, thisUTB, true))
                    }

                case otherObj@MObjectValueLike(thatUTB) =>
                    if(abstractsNullness(this, otherObj)){
                        classHierarchy.joinUpperTypeBounds(thisUTB, thatUTB, true) match {
                            case `thisUTB` => NoUpdate
                            case `thatUTB` => StructuralUpdate(other)
                            case newUTB    => asStructuralUpdate(pc, newUTB)
                        }
                    } else {
                        asStructuralUpdate(pc, classHierarchy.joinUpperTypeBounds(thisUTB, thatUTB, true))
                    }

                case otherObj: AnArrayValue =>
                    if(abstractsNullness(this, otherObj)){
                        classHierarchy.joinAnyArrayTypeWithMultipleTypesBound(thisUTB) match {
                            case `thisUTB` => NoUpdate
                            case newUTB    => asStructuralUpdate(pc, newUTB)
                        }
                    } else {
                        asStructuralUpdate(pc, classHierarchy.joinAnyArrayTypeWithMultipleTypesBound(thisUTB))
                    }


                case _: ANullValue =>
                    if(this.isNull.isNo) {
                        // Must make sure new object value is nullable!
                        asStructuralUpdate(pc, thisUTB)
                    } else
                        NoUpdate
            }
        }

        override def adapt(target: TargetDomain, origin: ValueOrigin): target.DomainValue =
            target match {
                case td: TypeLevelReferenceValues =>
                    td.ObjectValue(origin, upperTypeBound).asInstanceOf[target.DomainValue]
                case _ =>
                    super.adapt(target, origin)
            }

        override def summarize(origin: ValueOrigin): this.type = this

        override def toString: String = {
            upperTypeBound.map(_.toJava).mkString("ReferenceValue(", " with ", ")")
        }
    }

    object MObjectValueLike {
        def unapply(that: MObjectValueLike): Option[UIDSet[ObjectType]] = Some(that.upperTypeBound)
    }
}
