package com.jinelei.ducktype.processor;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

import com.jinelei.ducktype.annotation.DuckType;
import com.squareup.javapoet.*;

import java.io.IOException;
import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

@SupportedAnnotationTypes("com.jinelei.ducktype.annotation.DuckType")
public class DuckTypeProcessor extends AbstractProcessor {
    private Types typeUtils;
    private Elements elementUtils;
    private Filer filer;
    private Messager messager;
    private final Map<TypeMirror, List<TypeMirror>> interfaceSet = new HashMap<>();

    private BiFunction<TypeMirror, TypeMirror, Boolean> equalsFunction = (mirror1, mirror2) -> {
        if (!(mirror1 instanceof ExecutableType et1 && mirror2 instanceof ExecutableType et2)) {
            return false;
        }
        if (!typeUtils.isSameType(et1.getReturnType(), et2.getReturnType())) {
            return false;
        }
        if (et1.getParameterTypes().size() != et2.getParameterTypes().size()) {
            return false;
        }
        for (int i = 0; i < et1.getParameterTypes().size(); i++) {
            if (!typeUtils.isSameType(et1.getParameterTypes().get(i), et2.getParameterTypes().get(i))) {
                return false;
            }
        }
        return true;
    };

    @Override
    public SourceVersion getSupportedSourceVersion() {
        messager.printMessage(Diagnostic.Kind.NOTE, "getSupportedSourceVersion is running...");
        return SourceVersion.latestSupported();
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        typeUtils = processingEnv.getTypeUtils();
        elementUtils = processingEnv.getElementUtils();
        filer = processingEnv.getFiler();
        messager = processingEnv.getMessager();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        messager.printMessage(Diagnostic.Kind.NOTE, "DuckTypeProcessor process...");

        // 第一阶段：收集所有带有DuckType注解的接口及其方法
        collectDuckTypeInterfaces(roundEnv);

        // 第二阶段：检查所有类，为匹配的类生成增强类
        processClasses(roundEnv);

        return true;
    }

    private void collectDuckTypeInterfaces(RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(DuckType.class)) {
            if (element.getKind() != ElementKind.INTERFACE) {
                messager.printMessage(Diagnostic.Kind.ERROR, "@DuckType can only be applied to interfaces", element);
                continue;
            }

            TypeElement interfaceElement = (TypeElement) element;
            TypeMirror type = interfaceElement.asType();
            interfaceSet.putIfAbsent(type, new ArrayList<>());

            interfaceElement.getEnclosedElements().stream()
                    .filter(e -> e.getKind() == ElementKind.METHOD)
                    .map(e -> (ExecutableElement) e)
                    .map(ExecutableElement::asType)
                    .forEach(m -> interfaceSet.get(type).add(m));

            messager.printMessage(Diagnostic.Kind.NOTE, "Found DuckType interface: %s".formatted(element.getSimpleName()));
        }
    }

    private void processClasses(RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getRootElements()) {
            if (element.getKind() != ElementKind.CLASS) {
                continue;
            }

            TypeElement classElement = (TypeElement) element;
            if (classElement.getModifiers().contains(Modifier.FINAL)) {
                continue;
            }

            final Set<TypeMirror> classMethodSet = classElement.getEnclosedElements().stream()
                    .filter(e -> e.getKind() == ElementKind.METHOD)
                    .map(e -> (ExecutableElement) e)
                    .map(ExecutableElement::asType)
                    .collect(Collectors.toSet());

            messager.printMessage(Diagnostic.Kind.NOTE, "Found dest class %s".formatted(classElement.getSimpleName()));

            final Map<TypeMirror, List<TypeMirror>> matchedInterfaces = new HashMap<>();

            interfaceSet.forEach((key, interfaceMethods) -> {
                if (interfaceMethods.stream().allMatch(m1 -> classMethodSet.stream().anyMatch(m2 -> equalsFunction.apply(m1, m2)))) {
                    matchedInterfaces.put(key, interfaceMethods);
                }
            });

            if (!matchedInterfaces.isEmpty()) {
                generateEnhancedClass(classElement, matchedInterfaces);
            }
        }
    }

    private void generateEnhancedClass(TypeElement originalClass, Map<TypeMirror, List<TypeMirror>> interfaces) {
        String packageName = elementUtils.getPackageOf(originalClass).getQualifiedName().toString();
        String className = originalClass.getSimpleName().toString();
        messager.printMessage(Diagnostic.Kind.NOTE, "Generating enhanced class: %s for interfaces: %s".formatted(className, interfaces));


        // 检查文件是否已存在
        try {
            String qualifiedName = packageName + "." + className;
            filer.getResource(StandardLocation.CLASS_OUTPUT, "", qualifiedName.replace('.', '/') + ".java");
            messager.printMessage(Diagnostic.Kind.NOTE, "Enhanced class %s already exists, skipping generation.".formatted(qualifiedName));
            return;
        } catch (IOException ignored) {
            // 文件不存在，继续生成
        }

        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(className).addModifiers(Modifier.PUBLIC).superclass(ClassName.get(originalClass));

        interfaces.keySet().forEach(interfaceName -> {
            messager.printMessage(Diagnostic.Kind.NOTE, "Adding interface: %s".formatted(interfaceName));
            classBuilder.addSuperinterface(ClassName.get(interfaceName));
        });

//        // 添加方法和 @Override 注解
//        for (Map.Entry<TypeName, List<MethodSignature>> entry : interfaces.entrySet()) {
//            for (MethodSignature methodSignature : entry.getValue()) {
//                MethodSpec methodSpec = MethodSpec.methodBuilder(methodSignature.name())
//                        .addAnnotation(Override.class)
//                        .returns(methodSignature.returnType())
//                        .addParameters(methodSignature.parameterTypes().stream()
//                                .map(type -> ParameterSpec.builder(type, "arg").build())
//                                .collect(Collectors.toList()))
//                        .addStatement("return super.$L($L)", methodSignature.name(), methodSignature.parameterTypes().stream().map(t -> "arg").collect(Collectors.joining(", ")))
//                        .build();
//                classBuilder.addMethod(methodSpec);
//            }
//        }

        // 生成增强类的源代码
        messager.printMessage(Diagnostic.Kind.NOTE, "Generating source code for enhanced class: ---------------------------\n%s".formatted(classBuilder.build().toString()));
        try {
            JavaFile javaFile = JavaFile.builder(packageName, classBuilder.build()).build();
            javaFile.writeTo(filer);
        } catch (IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR, "Failed to generate enhanced class: %s".formatted(e.getMessage()));
        }
    }

}