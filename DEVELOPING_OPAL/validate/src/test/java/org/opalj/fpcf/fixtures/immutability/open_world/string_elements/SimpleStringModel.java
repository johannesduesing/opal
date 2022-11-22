/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj.fpcf.fixtures.immutability.open_world.string_elements;

import org.opalj.fpcf.properties.immutability.field_assignability.UnsafelyLazilyInitializedField;
import org.opalj.fpcf.properties.immutability.fields.MutableField;
import org.opalj.fpcf.properties.immutability.fields.NonTransitivelyImmutableField;
import org.opalj.fpcf.properties.immutability.fields.TransitivelyImmutableField;
import org.opalj.fpcf.properties.immutability.field_assignability.NonAssignableField;
import org.opalj.fpcf.properties.immutability.field_assignability.LazilyInitializedField;
import org.opalj.tac.fpcf.analyses.immutability.field_assignability.L2FieldAssignabilityAnalysis;

/**
 * This class represents a simple model of the class java.util.String.
 * It encompasses cases of a shared array and a lazy initialized field storing a hash value.
 */
public final class SimpleStringModel {

    @TransitivelyImmutableField(value = "The array values are not mutated anymore after the assignment ", analyses = {})
    @NonTransitivelyImmutableField("")
    @NonAssignableField("Field is final")
    private final char value[];

    public char[] getValue(){
        return value.clone();
    }

    @TransitivelyImmutableField(value = "Lazy initialized field with primitive type", analyses = {})
    @MutableField("The field is not non-assignable")
    @LazilyInitializedField(value = "Field is lazily initialized", analyses = {})
    @UnsafelyLazilyInitializedField(value = "The analysis cannot reconizes determinism",
            analyses = {L2FieldAssignabilityAnalysis.class})
    private int hash; // Default value 0

    public SimpleStringModel(SimpleStringModel original) {
        this.value = original.value;
    }

    public SimpleStringModel(char[] value){
        this.value = value.clone();
    }

    public int hashCode() {
        int h = 0;
        if (hash == 0) {
            char val[] = value;
            for (int i = 0; i < value.length; i++) {
                h = 31 * h + val[i];
            }
            hash = h;
        }
        return hash;
    }
}
