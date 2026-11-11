package com.diagramador.domain;

/** The stereotypes a classifier of kind CLASS or ABSTRACT_CLASS may carry (one per classifier). */
public enum ClassifierStereotype {
    ENTITY,
    BOUNDARY,
    CONTROL,
    SERVICE,
    UTILITY,
    TYPE,
    AUXILIARY,
    FOCUS,
    IMPLEMENTATION_CLASS,
    METACLASS,
    SIGNAL
}
