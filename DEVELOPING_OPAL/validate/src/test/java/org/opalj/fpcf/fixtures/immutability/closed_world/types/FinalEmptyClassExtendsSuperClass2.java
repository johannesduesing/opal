/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj.fpcf.fixtures.immutability.closed_world.types;

import org.opalj.fpcf.properties.immutability.classes.TransitivelyImmutableClass;
import org.opalj.fpcf.properties.immutability.types.TransitivelyImmutableType;

@TransitivelyImmutableType("class is final and transitive immutable")
@TransitivelyImmutableClass("class is empty")
public final class FinalEmptyClassExtendsSuperClass2 extends SuperClass {
}

