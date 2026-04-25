package com.wynncraft.core.interfaces;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(value = RetentionPolicy.RUNTIME)
public @interface Information {

    /**
     * @return the algorithm name
     */
    String name();

    /**
     * @return the algorithm authors
     */
    String[] authors();

}
