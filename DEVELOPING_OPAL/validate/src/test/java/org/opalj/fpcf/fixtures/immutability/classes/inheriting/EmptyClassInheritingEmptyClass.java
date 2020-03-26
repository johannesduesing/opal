package org.opalj.fpcf.fixtures.immutability.classes.inheriting;

import org.opalj.fpcf.properties.class_immutability.DeepImmutableClassAnnotation;
import org.opalj.fpcf.properties.type_immutability.MutableTypeAnnotation;

@MutableTypeAnnotation("Not final class")
@DeepImmutableClassAnnotation("empty class inheriting empty class")
public class EmptyClassInheritingEmptyClass extends EmptyClass{
}