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
import java.util.stream.Collectors;

import org.eclipse.jdt.core.dom.*;

@SupportedAnnotationTypes("com.jinelei.ducktype.annotation.DuckType")
public class DuckTypeProcessor extends AbstractProcessor {

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        // 扫描带DuckType注解的接口
        Set<TypeElement> duckTypeInterfaces = new HashSet<>();
        for (TypeElement annotation : annotations) {
            for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                if (element.getAnnotation(FunctionalInterface.class) != null && element.getKind() == ElementKind.INTERFACE) {
                    duckTypeInterfaces.add((TypeElement) element);
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "找到鸭子类型注解的接口：%s，当前数量: %d".formatted(element.getSimpleName(), duckTypeInterfaces.size()));
                }
            }
        }

        // 扫描所有类
        for (Element element : roundEnv.getRootElements()) {
            if (element.getKind() == ElementKind.CLASS) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "+++++++++++++++++++++++++++++++++++++++++");
                processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "处理类类型【%s】".formatted(element.getSimpleName()));
                TypeElement classElement = (TypeElement) element;
                for (TypeElement duckTypeInterface : duckTypeInterfaces) {
                    final List<Element> needEnhanceElementList = new ArrayList<>();
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "++++++++++++++++++++++++++++++++");
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "处理鸭子类型注解的接口【%s】".formatted(duckTypeInterface.getQualifiedName()));
                    // 检查类是否已实现该接口
                    boolean alreadyImplemented = false;
                    for (TypeMirror iface : classElement.getInterfaces()) {
                        if (iface.toString().equals(duckTypeInterface.getQualifiedName().toString())) {
                            alreadyImplemented = true;
                            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "类【%s】已实现接口【%s】".formatted(classElement.getSimpleName(), duckTypeInterface.getSimpleName()));
                            break;
                        }
                    }

                    if (!alreadyImplemented) {
                        // 检查方法签名是否匹配
                        for (Element methodElement : duckTypeInterface.getEnclosedElements()) {
                            if (methodElement.getKind() == ElementKind.METHOD) {
                                for (Element classMethodElement : classElement.getEnclosedElements()) {
                                    if (classMethodElement.getKind() == ElementKind.METHOD) {
                                        if (getMethodSignature((ExecutableElement) methodElement).equals(getMethodSignature((ExecutableElement) classMethodElement))) {
                                            needEnhanceElementList.add(classMethodElement);
                                            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "类【%s】未实现接口【%s】，方法匹配【%s】".formatted(classElement.getSimpleName(), duckTypeInterface.getSimpleName(), methodElement.getSimpleName()));
                                            break;
                                        }
                                    }
                                }
                            }
                        }

                        if (!needEnhanceElementList.isEmpty()) {
                            // 为类添加实现接口的代码
                            modifySourceCode(classElement, duckTypeInterface, needEnhanceElementList);
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
     * 获取方法签名
     *
     * @param methodElement 方法元素
     * @return 方法签名
     */
    private String getMethodSignature(ExecutableElement methodElement) {
        return "%s %s(%s)".formatted(
                methodElement.getReturnType().toString(),
                methodElement.getSimpleName(),
                methodElement.getParameters().stream().map(param -> "%s %s".formatted(param.asType().toString(), param.getSimpleName())).collect(Collectors.joining(", "))
        );
    }

    /**
     * 获取方法签名
     *
     * @param methodElement 方法元素
     * @return 方法签名
     */
    private String getMethodSignature(MethodDeclaration methodElement) {
        return "%s %s(%s)".formatted(
                methodElement.getReturnType2().toString(),
                methodElement.getName(),
                methodElement.typeParameters().stream().collect(Collectors.joining(", "))
        );
    }

    /**
     * 为类添加实现接口的代码
     *
     * @param classElement     类元素
     * @param interfaceElement 接口元素
     */
    private void modifySourceCode(TypeElement classElement, TypeElement interfaceElement, List<Element> needEnhanceElementList) throws RuntimeException {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "为类【%s】添加实现接口【%s】的代码".formatted(classElement.getSimpleName(), interfaceElement.getQualifiedName()));
        // 使用Eclipse JDT解析源代码
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(getClassSource(classElement).toCharArray());
        parser.setResolveBindings(true);
        CompilationUnit cu = (CompilationUnit) parser.createAST(null);
        AST ast = cu.getAST();

        cu.types().stream()
                .filter(t -> t instanceof TypeDeclaration)
                .filter(t -> ((TypeDeclaration) t).getName().toString().equals(classElement.getSimpleName().toString()))
                .forEach(t -> {
                    // 添加接口实现
                    SimpleName typeName = ast.newSimpleName(interfaceElement.getSimpleName().toString());
                    SimpleType interfaceType = ast.newSimpleType(typeName);
                    ((TypeDeclaration) t).superInterfaceTypes().add(interfaceType);

                    // 为实现的方法添加 @Override 注解
                    interfaceElement.getEnclosedElements().stream()
                            .filter(mm -> mm instanceof ExecutableElement)
                            .map(e -> ((ExecutableElement) e))
                            .forEach(mm -> {
                                Arrays.stream(((TypeDeclaration) t).getMethods()).filter(m -> getMethodSignature(m).equals(getMethodSignature(mm)))
                                        .forEach(m -> {
                                            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "方法签名匹配，准备添加Override：%s".formatted(m.getName().toString()));
                                            // 添加 @Override 注解
                                            NormalAnnotation overrideAnnotation = ast.newNormalAnnotation();
                                            overrideAnnotation.setTypeName(ast.newSimpleName("Override"));
                                            m.modifiers().add(0, overrideAnnotation);
                                        });
                            });
                });
//
//        // 查找类声明
//        List<TypeDeclaration> types = cu.types();
//        processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "类声明数量：%s".formatted(cu.types().size()));
//        for (TypeDeclaration type : types) {
//            if (type.getName().toString().equals(classElement.getSimpleName().toString())) {
//                processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "找到类声明：%s".formatted(type.getName().toString()));
//                // 添加接口实现
//                AST ast = cu.getAST();
//                SimpleName typeName = ast.newSimpleName(interfaceElement.getSimpleName().toString());
//                SimpleType interfaceType = ast.newSimpleType(typeName);
//                type.superInterfaceTypes().add(interfaceType);
//
//                // 为实现的方法添加 @Override 注解
//                for (Element methodElement : interfaceElement.getEnclosedElements()) {
//                    if (methodElement.getKind() == ElementKind.METHOD) {
//                        ExecutableElement interfaceMethod = (ExecutableElement) methodElement;
//                        processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "找到接口方法：%s".formatted(interfaceMethod.getSimpleName()));
//                        processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "类方法数量：%d".formatted(type.getMethods().length));
//                        for (MethodDeclaration classMethod : type.getMethods()) {
//                            if (classMethod.getName().toString().equals(interfaceMethod.getSimpleName().toString())
//                                    && classMethod.parameters().size() == interfaceMethod.getParameters().size()
//                            ) {
//                                // 添加 @Override 注解
//                                NormalAnnotation overrideAnnotation = ast.newNormalAnnotation();
//                                overrideAnnotation.setTypeName(ast.newSimpleName("Override"));
//                                classMethod.modifiers().add(0, overrideAnnotation);
//                            }
//                        }
//                    }
//                }
//            }
//        }

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

}
