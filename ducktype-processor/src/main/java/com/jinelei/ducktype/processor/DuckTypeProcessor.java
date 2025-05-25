package com.jinelei.ducktype.processor;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.eclipse.jdt.core.dom.*;

@SupportedAnnotationTypes("com.jinelei.ducktype.annotation.DuckType")
public class DuckTypeProcessor extends AbstractProcessor {
    private static final Predicate<MethodDeclaration> overrideAnnotationPresent = mmm -> mmm.modifiers().stream().filter(i -> i instanceof Annotation).noneMatch(i -> ((Annotation) i).getTypeName().toString().equals("Override"));


    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        // 扫描带DuckType注解的接口
        Set<TypeElement> interfaceTypeElements = new HashSet<>();
        for (TypeElement annotation : annotations) {
            for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                if (element.getKind() == ElementKind.INTERFACE) {
                    interfaceTypeElements.add((TypeElement) element);
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "找到鸭子类型注解的接口：%s，当前数量: %d".formatted(element.getSimpleName(), interfaceTypeElements.size()));
                }
            }
        }

        // 扫描所有类
        for (Element element : roundEnv.getRootElements()) {
            if (element.getKind() == ElementKind.CLASS) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "+++++++++++++++++++++++++++++++++++++++++");
                processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "处理类类型【%s】".formatted(element.getSimpleName()));
                TypeElement classElement = (TypeElement) element;
                for (TypeElement interfaceElement : interfaceTypeElements) {
                    final List<Element> needEnhanceElementList = new ArrayList<>();
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "++++++++++++++++++++++++++++++++");
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "处理鸭子类型注解的接口【%s】".formatted(interfaceElement.getQualifiedName()));
                    // 检查类是否已实现该接口
                    boolean alreadyImplemented = classElement.getInterfaces().stream().anyMatch(t -> t.toString().equals(interfaceElement.getQualifiedName().toString()));
                    if (alreadyImplemented) {
                        processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "类【%s】已实现接口【%s】".formatted(classElement.getSimpleName(), interfaceElement.getSimpleName()));
                    } else {
                        processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "类【%s】未实现接口【%s】".formatted(classElement.getSimpleName(), interfaceElement.getSimpleName()));
                        // 检查方法签名是否匹配
                        List<String> classMethodSignatureList = classElement.getEnclosedElements().stream().filter(t -> t.getKind() == ElementKind.METHOD).map(t -> getMethodSignature((ExecutableElement) t)).toList();
                        List<String> interfaceMethodSignatureList = interfaceElement.getEnclosedElements().stream().filter(t -> t.getKind() == ElementKind.METHOD).map(t -> getMethodSignature((ExecutableElement) t)).toList();
                        processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "类【%s】方法签名：%s".formatted(classElement.getSimpleName(), classMethodSignatureList.stream().collect(Collectors.joining(","))));
                        processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "接口【%s】方法签名：%s".formatted(interfaceElement.getSimpleName(), interfaceMethodSignatureList.stream().collect(Collectors.joining(","))));
                        boolean allInterfaceMethodsExist = classMethodSignatureList.containsAll(interfaceMethodSignatureList);
                        if (!allInterfaceMethodsExist) {
                            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "类【%s】未实现接口【%s】所有方法，无需增强".formatted(classElement.getSimpleName(), interfaceElement.getSimpleName()));
                        } else {
                            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "类【%s】已实现接口【%s】所有方法，正在增强".formatted(classElement.getSimpleName(), interfaceElement.getSimpleName()));
                            // 为类添加实现接口的代码
                            modifySourceCode(classElement, interfaceElement);
                        }
                    }
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "--------------------------------");
                }
                processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "-----------------------------------------");
            }
        }
        return true;
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    /**
     * 为类添加实现接口的代码
     *
     * @param classElement     类元素
     * @param interfaceElement 接口元素
     */
    private void modifySourceCode(TypeElement classElement, TypeElement interfaceElement) throws RuntimeException {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "为类【%s】添加实现接口【%s】的代码".formatted(classElement.getSimpleName(), interfaceElement.getQualifiedName()));
        // 使用Eclipse JDT解析源代码
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(getClassSource(classElement).toCharArray());
        parser.setResolveBindings(true);
        CompilationUnit cu = (CompilationUnit) parser.createAST(null);
        AST ast = cu.getAST();

        final Map<String, List<ExecutableElement>> interfaceMethodBySignatureMap = interfaceElement.getEnclosedElements().stream()
                .filter(mm -> mm instanceof ExecutableElement)
                .map(e -> ((ExecutableElement) e))
                .collect(Collectors.groupingBy(this::getMethodSignature));

        cu.types().stream()
                .filter(t -> t instanceof TypeDeclaration)
                .filter(t -> ((TypeDeclaration) t).getName().toString().equals(classElement.getSimpleName().toString()))
                .forEach(t -> {
                    // 添加接口实现
                    SimpleName typeName = ast.newSimpleName(interfaceElement.getSimpleName().toString());
                    SimpleType interfaceType = ast.newSimpleType(typeName);
                    ((TypeDeclaration) t).superInterfaceTypes().add(interfaceType);

                    // 找到该类的所有方法
                    Map<String, List<MethodDeclaration>> classMethodBySignatureMap = Arrays.stream(((TypeDeclaration) t).getMethods()).collect(Collectors.groupingBy(m -> getMethodSignature(m, cu)));

                    // 为实现的方法添加 @Override 注解
                    interfaceElement.getEnclosedElements().stream()
                            .filter(mm -> mm instanceof ExecutableElement)
                            .map(e -> ((ExecutableElement) e))
                            .forEach(mm -> {
                                final String interfaceMethodSignature = getMethodSignature(mm);
                                processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "找到接口方法：%s，找到类方法: %d".formatted(interfaceMethodSignature, classMethodBySignatureMap.getOrDefault(interfaceMethodSignature, new ArrayList<>()).size()));
                                Arrays.stream(((TypeDeclaration) t).getMethods())
                                        .filter(mmm -> interfaceMethodSignature.equals(getMethodSignature(mm)))
                                        .filter(overrideAnnotationPresent)
                                        .forEach(mmm -> {
                                            // 添加 @Override 注解
                                            NormalAnnotation overrideAnnotation = ast.newNormalAnnotation();
                                            overrideAnnotation.setTypeName(ast.newSimpleName("Override"));
                                            mmm.modifiers().add(0, overrideAnnotation);
                                        });
                            });
                });

        try {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "修改后的代码：--------------------\n%s".formatted(cu.toString()));
            // 保存到target/generated-sources
            File dir = new File("target/generated-sources");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            try (FileWriter writer = new FileWriter(new File(dir, classElement.getSimpleName().toString() + ".java"))) {
                writer.write(cu.toString());
            } catch (Exception e) {
                e.printStackTrace();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String getClassSource(TypeElement classElement) throws RuntimeException {
        try {
            // 获取类的全限定名
            String qualifiedName = classElement.getQualifiedName().toString();
            // 使用 Filer 打开源文件
            FileObject fileObject = processingEnv.getFiler().getResource(StandardLocation.SOURCE_PATH, "", qualifiedName.replace('.', '/') + ".java");
            // 读取文件内容
            try (InputStream in = fileObject.openInputStream()) {
                byte[] bytes = in.readAllBytes();
                String sourceCode = new String(bytes);
                processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "类源码：--------------------\n%s".formatted(sourceCode));
                return sourceCode;
            }
        } catch (Exception e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "获取源码失败: " + e.getMessage());
            throw new RuntimeException("Source file not found: " + classElement.getQualifiedName().toString());
        }
    }

    /**
     * 获取 MethodDeclaration 的全限定方法签名
     *
     * @param methodDeclaration 方法声明
     * @param compilationUnit   编译单元，用于获取类型绑定信息
     * @return 全限定方法签名
     */
    private String getMethodSignature(MethodDeclaration methodDeclaration, CompilationUnit compilationUnit) {
        StringBuilder signature = new StringBuilder();

        // 获取全限定返回类型
        Type returnType2 = Optional.ofNullable(methodDeclaration).map(MethodDeclaration::getReturnType2).orElseThrow(() -> new RuntimeException("返回类型未找到"));
        String returnType = Optional.of(returnType2)
                .map(Type::resolveBinding)
                .map(ITypeBinding::getQualifiedName)
                .orElse(returnType2.toString());
        signature.append(returnType).append(" ");

        // 获取方法名
        signature.append(methodDeclaration.getName().getIdentifier());

        // 获取全限定参数类型
        signature.append("(");
        List<?> parameters = methodDeclaration.parameters();
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) {
                signature.append(", ");
            }
            org.eclipse.jdt.core.dom.SingleVariableDeclaration param = (org.eclipse.jdt.core.dom.SingleVariableDeclaration) parameters.get(i);
            Type type = Optional.ofNullable(param).map(SingleVariableDeclaration::getType).orElseThrow(RuntimeException::new);
            String paramType = Optional.of(type)
                    .map(Type::resolveBinding)
                    .map(ITypeBinding::getQualifiedName)
                    .orElse(type.toString());
            signature.append(paramType);
        }
        signature.append(")");

        // 获取全限定异常类型
        List<?> exceptions = methodDeclaration.thrownExceptionTypes();
        if (!exceptions.isEmpty()) {
            signature.append(" throws ");
            for (int i = 0; i < exceptions.size(); i++) {
                if (i > 0) {
                    signature.append(", ");
                }
                org.eclipse.jdt.core.dom.Name exceptionType = (org.eclipse.jdt.core.dom.Name) exceptions.get(i);
                String exceptionTypeName = getFullyQualifiedType(exceptionType.resolveTypeBinding(), compilationUnit);
                signature.append(exceptionTypeName);
            }
        }

        return signature.toString();
    }

    /**
     * 获取方法签名，包含全限定名
     *
     * @param methodElement 方法元素
     * @return 包含全限定名的方法签名
     */
    private String getMethodSignature(ExecutableElement methodElement) {
        Elements elementUtils = processingEnv.getElementUtils();
        Types typeUtils = processingEnv.getTypeUtils();

        // 获取返回类型的全限定名
        String returnType = getFullyQualifiedTypeName(methodElement.getReturnType(), typeUtils, elementUtils);

        // 获取方法名
        String methodName = methodElement.getSimpleName().toString();

        // 获取参数类型的全限定名
        String params = methodElement.getParameters().stream()
                .map(param -> {
                    String paramType = getFullyQualifiedTypeName(param.asType(), typeUtils, elementUtils);
                    return paramType;
                })
                .collect(Collectors.joining(", "));

        // 获取抛出异常类型的全限定名
        String exceptions = methodElement.getThrownTypes().stream()
                .map(exceptionType -> getFullyQualifiedTypeName(exceptionType, typeUtils, elementUtils))
                .collect(Collectors.joining(", "));

        // 拼接方法签名
        String signature = "%s %s(%s)".formatted(returnType, methodName, params);
        if (!exceptions.isEmpty()) {
            signature += " throws " + exceptions;
        }
        return signature;
    }

    /**
     * 获取类型绑定的全限定名
     *
     * @param binding         类型绑定
     * @param compilationUnit 编译单元，用于获取类型绑定信息
     * @return 全限定类型名
     */
    private String getFullyQualifiedType(org.eclipse.jdt.core.dom.ITypeBinding binding, CompilationUnit compilationUnit) {
        if (binding != null) {
            return binding.getQualifiedName();
        }
        return "";
    }


    /**
     * 获取类型的全限定名
     *
     * @param typeMirror   类型镜像
     * @param typeUtils    类型工具类
     * @param elementUtils 元素工具类
     * @return 类型的全限定名
     */
    private String getFullyQualifiedTypeName(TypeMirror typeMirror, Types typeUtils, Elements elementUtils) {
        // 处理基本类型
        if (typeMirror.getKind().isPrimitive()) {
            return typeMirror.toString();
        }
//        // 处理数组类型
//        if (typeMirror.getKind() == TypeKind.ARRAY) {
//            ArrayType arrayType = (ArrayType) typeMirror;
//            String componentType = getFullyQualifiedTypeName(arrayType.getComponentType(), typeUtils, elementUtils);
//            return componentType + "[]";
//        }
        // 处理其他类型
        Element element = typeUtils.asElement(typeMirror);
        if (element instanceof TypeElement) {
            return ((TypeElement) element).getQualifiedName().toString();
        }
        return typeMirror.toString();
    }
}