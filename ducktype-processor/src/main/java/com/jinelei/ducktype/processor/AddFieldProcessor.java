package com.jinelei.ducktype.processor;

import com.jinelei.ducktype.annotation.AddField;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@SupportedAnnotationTypes("com.jinelei.ducktype.annotation.AddField")
public class AddFieldProcessor extends AbstractProcessor {
    private Messager messager;

    private final Function<ExecutableElement, String> methodSignatureFunction = (executableElement) -> {
        if (executableElement.asType() instanceof ExecutableType executableType) {
            TypeMirror returnType = executableType.getReturnType();
            String methodName = executableElement.getSimpleName().toString();
            String paramList = executableType.getParameterTypes().stream().map(TypeMirror::toString).collect(Collectors.joining(","));
            return "%s %s(%s)".formatted(returnType.toString(), methodName, paramList);
        }
        return null;
    };

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        messager = processingEnv.getMessager();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        messager.printMessage(Diagnostic.Kind.NOTE, "AddFieldProcessor process...");

        for (Element element : roundEnv.getElementsAnnotatedWith(AddField.class)) {
            if (element instanceof TypeElement) {
                TypeElement typeElement = (TypeElement) element;
                try {
                    // 创建 name 字段
                    FieldSpec nameField = FieldSpec.builder(String.class, "name", Modifier.PRIVATE)
                            .build();

                    // 创建新的类
                    TypeSpec newClass = TypeSpec.classBuilder(typeElement.getQualifiedName().toString())
                            .addModifiers(Modifier.PUBLIC)
                            .addField(nameField)
                            .build();

                    // 获取包名
                    String packageName = processingEnv.getElementUtils().getPackageOf(typeElement).getQualifiedName().toString();

                    // 生成 Java 文件
                    JavaFile javaFile = JavaFile.builder(packageName, newClass).build();

                    // 指定生成路径为 target/generated-sources/annotations
                    String generatedSourcesDir = processingEnv.getOptions().get("targetDir") + "/generated-sources/annotations";
                    messager.printMessage(Diagnostic.Kind.NOTE, "generatedSourcesDir: " + generatedSourcesDir);
                    java.io.File outputDir = new java.io.File(generatedSourcesDir, packageName.replace(".", "/"));
                    messager.printMessage(Diagnostic.Kind.NOTE, "outputDir: " + outputDir.getAbsolutePath());
                    if (!outputDir.mkdirs()) {
                        messager.printMessage(Diagnostic.Kind.ERROR, "创建目录失败: " + outputDir.getAbsolutePath());
                    }
                    javaFile.writeTo(outputDir);
                } catch (IOException e) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "生成代码时出错: " + e.getMessage(), element);
                }
            }
        }

        return true;
    }

}