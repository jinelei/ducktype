package com.jinelei.ducktype.processor;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import java.util.*;

@SupportedAnnotationTypes("com.jinelei.ducktype.annotation.DuckType")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class DuckTypeProcessor extends AbstractProcessor {

    public DuckTypeProcessor() {
        super();
    }


    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        return true;
    }

}
