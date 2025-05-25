package com.jinelei.ducktype.processor;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.eclipse.jdt.core.dom.*;

@SupportedAnnotationTypes("com.jinelei.ducktype.annotation.DuckType")
public class DuckTypeProcessor extends AbstractProcessor {
    /**
     * 安全地将 List 转换为 List<Object> 类型，不创建新对象
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final Function<List, List<Object>> safeConvertToListObject = (List rawList) -> (List<Object>) rawList;

    private static final Predicate<MethodDeclaration> overrideAnnotationPresent = mmm -> safeConvertToListObject.apply(mmm.modifiers()).stream().filter(i -> i instanceof Annotation).noneMatch(i -> ((Annotation) i).getTypeName().toString().equals("Override"));


    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        // 扫描带DuckType注解的接口
        Set<TypeElement> interfaceTypeElements = new HashSet<>();
        for (TypeElement annotation : annotations) {
            for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                if (element.getKind() == ElementKind.INTERFACE) {
                    interfaceTypeElements.add((TypeElement) element);
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "找到鸭子类型注解的接口：%s，当前数量: %d".formatted(element.getSimpleName(), interfaceTypeElements.size()));
                }
            }
        }

        // 扫描所有类
        for (Element element : roundEnv.getRootElements()) {
            if (element.getKind() == ElementKind.CLASS) {
                TypeElement classElement = (TypeElement) element;
                for (TypeElement interfaceElement : interfaceTypeElements) {
                    // 检查类是否已实现该接口
                    boolean alreadyImplemented = classElement.getInterfaces().stream().anyMatch(t -> t.toString().equals(interfaceElement.getQualifiedName().toString()));
                    if (!alreadyImplemented) {
                        // 检查方法签名是否匹配
                        List<String> classMethodSignatureList = classElement.getEnclosedElements().stream().filter(t -> t.getKind() == ElementKind.METHOD).map(t -> getMethodSignature((ExecutableElement) t)).toList();
                        List<String> interfaceMethodSignatureList = interfaceElement.getEnclosedElements().stream().filter(t -> t.getKind() == ElementKind.METHOD).map(t -> getMethodSignature((ExecutableElement) t)).toList();
                        boolean allInterfaceMethodsExist = new HashSet<>(classMethodSignatureList).containsAll(interfaceMethodSignatureList);
                        if (allInterfaceMethodsExist) {
                            // 为类添加实现接口的代码
                            modifySourceCode(classElement, interfaceElement);
                        }
                    }
                }
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
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "为类【%s】添加实现接口【%s】".formatted(classElement.getSimpleName(), interfaceElement.getSimpleName()));
        // 使用Eclipse JDT解析源代码
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(getClassSource(classElement).toCharArray());
        parser.setResolveBindings(true);
        CompilationUnit cu = (CompilationUnit) parser.createAST(null);
        AST ast = cu.getAST();
        safeConvertToListObject.apply(cu.types()).stream()
                .filter(t -> t instanceof TypeDeclaration)
                .map(t -> (TypeDeclaration) t)
                .filter(t -> t.getName().toString().equals(classElement.getSimpleName().toString()))
                .forEach(t -> {
                    // 添加接口实现
                    SimpleName typeName = ast.newSimpleName(interfaceElement.getSimpleName().toString());
                    SimpleType interfaceType = ast.newSimpleType(typeName);
                    safeConvertToListObject.apply(t.superInterfaceTypes()).add(interfaceType);

                    // 找到该类的所有方法
                    Map<String, List<MethodDeclaration>> classMethodBySignatureMap = Arrays.stream(t.getMethods()).collect(Collectors.groupingBy(this::getMethodSignature));

                    // 为实现的方法添加 @Override 注解
                    interfaceElement.getEnclosedElements().stream()
                            .filter(mm -> mm instanceof ExecutableElement)
                            .map(e -> ((ExecutableElement) e))
                            .forEach(mm -> {
                                final String interfaceMethodSignature = getMethodSignature(mm);
                                Arrays.stream(t.getMethods())
                                        .filter(mmm -> interfaceMethodSignature.equals(getMethodSignature(mm)))
                                        .filter(overrideAnnotationPresent)
                                        .forEach(mmm -> {
                                            // 添加 @Override 注解
                                            NormalAnnotation overrideAnnotation = ast.newNormalAnnotation();
                                            overrideAnnotation.setTypeName(ast.newSimpleName("Override"));
                                            safeConvertToListObject.apply(mmm.modifiers()).add(0, overrideAnnotation);
                                        });
                            });
                });

        try {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "修改后的代码：--------------------\n%s".formatted(cu.toString()));
            // 保存到target/generated-sources
            File dir = new File("target/generated-sources");
            if (!dir.exists()) {
                if (!dir.mkdirs()) {
                    throw new RuntimeException("创建目录失败: " + dir.getAbsolutePath());
                }
            }
            try (FileWriter writer = new FileWriter(new File(dir, classElement.getSimpleName().toString() + ".java"))) {
                writer.write(cu.toString());
            } catch (Exception e) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "写入文件失败: " + e.getMessage());
            }
        } catch (Exception e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "修改源代码失败: " + e.getMessage());
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
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "类源码：--------------------\n%s".formatted(sourceCode));
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
     * @return 全限定方法签名
     */
    private String getMethodSignature(MethodDeclaration methodDeclaration) {
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
                String exceptionTypeName = Optional.ofNullable(exceptionType).map(Expression::resolveTypeBinding).map(ITypeBinding::getQualifiedName).orElse("");
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
        if (methodElement == null) {
            return "";
        }
        // 获取返回类型的全限定名
        String returnType = Optional.of(methodElement).map(ExecutableElement::getReturnType).map(TypeMirror::toString).orElse("");

        // 获取方法名
        String methodName = methodElement.getSimpleName().toString();

        // 获取参数类型的全限定名
        String params = methodElement.getParameters().stream()
                .map(t -> Optional.ofNullable(t).map(VariableElement::asType).map(TypeMirror::toString).orElse(""))
                .collect(Collectors.joining(", "));

        // 获取抛出异常类型的全限定名
        String exceptions = methodElement.getThrownTypes().stream()
                .map(exceptionType -> Optional.ofNullable(exceptionType).map(TypeMirror::toString).orElse(""))
                .collect(Collectors.joining(", "));

        // 拼接方法签名
        String signature = "%s %s(%s)".formatted(returnType, methodName, params);
        if (!exceptions.isEmpty()) {
            signature += " throws " + exceptions;
        }
        return signature;
    }

}