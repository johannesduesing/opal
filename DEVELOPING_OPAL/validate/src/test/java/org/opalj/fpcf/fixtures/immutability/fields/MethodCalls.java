/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj.fpcf.fixtures.immutability.fields;

import org.opalj.fpcf.properties.immutability.fields.MutableField;
import org.opalj.fpcf.properties.immutability.fields.NonTransitivelyImmutableField;
import org.opalj.fpcf.properties.immutability.field_assignability.EffectivelyNonAssignableField;
import org.opalj.fpcf.properties.immutability.field_assignability.UnsafelyLazilyInitializedField;
import org.opalj.fpcf.properties.immutability.field_assignability.LazilyInitializedField;
import org.opalj.fpcf.properties.immutability.field_assignability.AssignableField;

public class MethodCalls {
    @NonTransitivelyImmutableField("")
    @LazilyInitializedField("")
    private TestMutable tm1;

    public synchronized void getTM1(){
        if(tm1==null){
            tm1= new TestMutable();
        }
        tm1.nop();
    }

    @NonTransitivelyImmutableField("")
    @LazilyInitializedField("")
    private TestMutable tm2;

    public synchronized TestMutable getTM2(){
        if(tm2==null){
            tm2= new TestMutable();
        }
        return tm2;
    }

    @MutableField("")
    @UnsafelyLazilyInitializedField("")
    private TestMutable tm3;

    public void getTm3() {
        if(tm3==null){
            tm3 = new TestMutable();
        }
    }

    @AssignableField("")
    @MutableField("")
    private TestMutable tm4;

    public synchronized TestMutable getTm4() {
        if(tm4==null){
            tm4 = new TestMutable();
        }
        return tm4;
    }

    public synchronized TestMutable getTm42() {
        if(tm4==null){
            tm4 = new TestMutable();
        }
        return tm4;
    }

    @NonTransitivelyImmutableField("")
    private TestMutable tm5;

    public synchronized void getTm5() {
        if(tm5==null){
            tm5 = new TestMutable();
        }
        tm5.nop();
    }

    @NonTransitivelyImmutableField("")
    @EffectivelyNonAssignableField("")
    private TestMutable tm6 = new TestMutable();

    @NonTransitivelyImmutableField("")
    @EffectivelyNonAssignableField("")
    private TestMutable tm7 = new TestMutable();

    public void foo(){
        tm7.nop();
    }








}

class TestMutable{
    private int n = 5;

    public void setN(int n){
        this.n = n;
    }

    public void nop(){
    }
}